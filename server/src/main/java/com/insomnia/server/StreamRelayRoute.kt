package com.insomnia.server

import com.insomnia.content.contract.ByteSink
import com.insomnia.content.contract.StreamRelayRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * `/relay/{token}` — looks up a recipe in [StreamRelayRegistry], calls [serve], and pumps the
 * result to the response. Every relay (JS spider, the `fs` file-service recipe) flows through
 * this one route; the server holds no provider-specific logic.
 */
class StreamRelayRoute {

    internal fun Application.installRoutes() {
        routing {
            installTokenRoute()
        }
    }

    private fun Route.installTokenRoute() {
        get("/relay/{token}") {
            val token = call.parameters["token"]
                ?: return@get call.respondBytes("missing token".toByteArray(), status = HttpStatusCode.BadRequest)
            val recipe = StreamRelayRegistry.get(token)
                ?: return@get call.respondBytes("unknown token".toByteArray(), status = HttpStatusCode.NotFound)

            val params = HashMap<String, String>()
            for (name in call.parameters.names()) call.parameters[name]?.let { params[name] = it }
            for (name in call.request.headers.names()) call.request.headers[name]?.let { params[name] = it }

            val range = params["Range"]
            Timber.d("sr: req range=${range ?: "none"}")
            Timber.d("sr: → remote range=${range ?: "none"}")

            val result = withContext(Dispatchers.IO) { recipe.serve(params) }
            if (result == null) {
                Timber.d("sr: remote returned null")
                call.respondBytes("relay returned no content".toByteArray(), status = HttpStatusCode.NotFound)
                return@get
            }

            Timber.d("sr: ← remote status=${result.status} cr=${result.headers["Content-Range"]}")

            try {
                call.response.status(HttpStatusCode.fromValue(result.status))
                for ((k, v) in result.headers) call.response.header(k, v)
                val ct = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                call.respondBytesWriter(contentType = ct, contentLength = result.length) {
                    result.pump(sinkAdapter())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Timber.d("sr: cancelled")
            } catch (e: Exception) {
                if (isBrokenPipe(e)) {
                    Timber.d("sr: stream disconnected by client")
                } else {
                    Timber.e(e, "sr: stream failed")
                }
            }
            Timber.d("sr: resp → player status=${result.status} cr=${result.headers["Content-Range"]} length=${result.length} source=remote")
        }
    }

    private fun isBrokenPipe(e: Throwable): Boolean =
        e is io.ktor.util.cio.ChannelWriteException ||
            e is io.ktor.utils.io.ClosedWriteChannelException ||
            e is io.ktor.utils.io.ClosedByteChannelException ||
            e.cause is io.ktor.utils.io.ClosedWriteChannelException ||
            e.cause is io.ktor.utils.io.ClosedByteChannelException ||
            e is java.net.SocketException ||
            e.cause is java.net.SocketException

    private fun ByteWriteChannel.sinkAdapter(): ByteSink =
        ByteSink { buf, off, len -> writeFully(buf, off, off + len) }
}
