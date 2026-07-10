package com.insomnia.server

import com.insomnia.content.contract.ByteSink
import com.insomnia.content.contract.StreamRelayRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
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
 * `/relay/{token}` resolves a recipe from [StreamRelayRegistry] and pumps it to the response.
 * Token is registered by the caller (e.g. `host.relay.register({token: "catvod", ...})`).
 */
class StreamRelayRoute {

    internal fun io.ktor.server.application.Application.installRoutes() {
        routing {
            installRelayRoute()
        }
    }

    private fun Route.installRelayRoute() {
        get("/relay/{token}") {
            val token = call.parameters["token"]
                ?: return@get call.respondBytes("missing token".toByteArray(), status = HttpStatusCode.BadRequest)
            call.serveToken(token)
        }
    }

    private suspend fun ApplicationCall.serveToken(token: String) {
        val recipe = StreamRelayRegistry.get(token)
            ?: return respondBytes("unknown token".toByteArray(), status = HttpStatusCode.NotFound)

        val params = HashMap<String, String>()
        for (name in request.queryParameters.names()) request.queryParameters[name]?.let { params[name] = it }
        for (name in request.headers.names()) request.headers[name]?.let { params[name] = it }

        val range = params["Range"]
        Timber.d("sr: token=$token req range=${range ?: "none"}")
        Timber.d("sr: token=$token → remote range=${range ?: "none"}")

        val result = withContext(Dispatchers.IO) { recipe.serve(params) }
        if (result == null) {
            Timber.d("sr: token=$token remote returned null")
            respondBytes("relay returned no content".toByteArray(), status = HttpStatusCode.NotFound)
            return
        }
        Timber.d("sr: token=$token ← remote status=${result.status} cr=${result.headers["Content-Range"]}")

        try {
            response.status(HttpStatusCode.fromValue(result.status))
            for ((k, v) in result.headers) response.header(k, v)
            val ct = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            respondBytesWriter(contentType = ct, contentLength = result.length) {
                result.pump(sinkAdapter())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.d("sr: token=$token cancelled")
        } catch (e: Exception) {
            if (isBrokenPipe(e)) {
                Timber.d("sr: token=$token stream disconnected by client")
            } else {
                Timber.e(e, "sr: token=$token stream failed")
            }
        }
        Timber.d("sr: token=$token resp → player status=${result.status} cr=${result.headers["Content-Range"]} length=${result.length} source=remote")
    }

    private fun isBrokenPipe(e: Throwable): Boolean =
        e is io.ktor.util.cio.ChannelWriteException ||
            e is io.ktor.utils.io.ClosedWriteChannelException ||
            e is io.ktor.utils.io.ClosedByteChannelException ||
            e.cause is io.ktor.util.cio.ChannelWriteException ||
            e.cause is io.ktor.utils.io.ClosedWriteChannelException ||
            e is java.net.SocketException ||
            e.cause is java.net.SocketException

    private fun ByteWriteChannel.sinkAdapter(): ByteSink =
        ByteSink { buf, off, len -> writeFully(buf, off, off + len) }
}
