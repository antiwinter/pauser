package com.opentune.server

import com.opentune.content.contract.StreamRelayRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream

private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * Generic stream-relay route: `/relay/{token}`. Looks up a [com.opentune.content.contract.StreamRelayRecipe]
 * by token, calls `serve(params)`, and streams the result back to the player unmodified.
 *
 * Provider-agnostic and content-agnostic: the server knows nothing about catvod and never
 * rewrites response bodies — it only invokes the registered recipe and pumps bytes through.
 */
class StreamRelayRoute {
    internal fun Application.installRoutes() {
        routing {
            get("/relay/{token}") {
                val token = call.parameters["token"]
                    ?: return@get call.respondBytes("missing token".toByteArray(), status = HttpStatusCode.BadRequest)
                val recipe = StreamRelayRegistry.get(token)
                    ?: return@get call.respondBytes("unknown token".toByteArray(), status = HttpStatusCode.NotFound)

                // fongmi merges parms + headers into one Map (process/Proxy.java:25-27)
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
                    call.respondBytesWriter(contentType = ct) {
                        pump(result.stream)
                    }
                } catch (e: Throwable) {
                    Timber.e(e, "relay route stream failed token=$token")
                } finally {
                    withContext(Dispatchers.IO) { runCatching { result.stream.close() } }
                }
            }
        }
    }

    private suspend fun io.ktor.utils.io.ByteWriteChannel.pump(stream: InputStream) {
        val buf = ByteArray(PUMP_CHUNK_SIZE)
        while (true) {
            val read = withContext(Dispatchers.IO) { stream.read(buf) }
            if (read <= 0) break
            writeFully(buf, 0, read)
        }
    }
}
