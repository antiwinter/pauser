package com.opentune.server

import com.opentune.content.contract.ByteSink
import com.opentune.content.contract.StreamRelayRegistry
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

            // fongmi merges params + headers into one Map (process/Proxy.java:25-27)
            val params = HashMap<String, String>()
            for (name in call.parameters.names()) call.parameters[name]?.let { params[name] = it }
            for (name in call.request.headers.names()) call.request.headers[name]?.let { params[name] = it }

            val result = withContext(Dispatchers.IO) { recipe.serve(params) }
            if (result == null) {
                call.respondBytes("relay returned no content".toByteArray(), status = HttpStatusCode.NotFound)
                return@get
            }

            try {
                call.response.status(HttpStatusCode.fromValue(result.status))
                for ((k, v) in result.headers) call.response.header(k, v)
                val ct = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                call.respondBytesWriter(contentType = ct, contentLength = result.length) {
                    result.pump(sinkAdapter())
                }
            } catch (e: Throwable) {
                Timber.e(e, "relay route stream failed token=$token")
            }
        }
    }

    private fun ByteWriteChannel.sinkAdapter(): ByteSink =
        ByteSink { buf, off, len -> writeFully(buf, off, len) }
}
