package com.opentune.server

import android.util.Log
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.ProviderStream
import com.opentune.provider.StreamRegistrar
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "OpenTuneServer"
private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * Token-based stream proxy that bridges provider byte-streams to plain HTTP URLs.
 *
 * Implements [StreamRegistrar]: providers call [registerStream] to obtain a
 * `http://127.0.0.1:<port>/stream/<token>` URL, and [revokeToken] when done.
 *
 * Route surface is internal to `:server` — installed by [OpenTuneServer] via [installRoutes].
 */
class StreamProxy : StreamRegistrar {

    private data class TokenEntry(
        val instance: OpenTuneProviderInstance,
        val itemRef: String,
    )

    private val registry = ConcurrentHashMap<String, TokenEntry>()

    // --- StreamRegistrar ---

    override fun registerStream(instance: OpenTuneProviderInstance, itemRef: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        registry[token] = TokenEntry(instance, itemRef)
        return "http://127.0.0.1:${SERVER_PORT}/stream/$token"
    }

    override fun revokeToken(url: String) {
        val token = url.substringAfterLast('/')
        registry.remove(token)
        Log.d(LOG_TAG, "revoked token=$token registry.size=${registry.size}")
    }

    // --- Ktor route installer (internal to :server) ---

    internal fun Application.installRoutes() {
        routing {
            get("/stream/{token}") {
                val token = call.parameters["token"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val entry = registry[token]
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                val stream: ProviderStream = withContext(Dispatchers.IO) {
                    entry.instance.openStream(entry.itemRef)
                } ?: return@get call.respond(HttpStatusCode.NotFound)

                try {
                    val totalSize = withContext(Dispatchers.IO) { stream.getSize() }
                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    if (rangeHeader != null) {
                        val (start, end) = parseRange(rangeHeader, totalSize)
                        val length = end - start + 1
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                        Log.d(LOG_TAG, "stream token=$token range=$start-$end/$totalSize")
                        call.respondBytesWriter(
                            contentType = ContentType.Application.OctetStream,
                            status = HttpStatusCode.PartialContent,
                            contentLength = length,
                        ) {
                            pumpStream(stream, start, length)
                        }
                    } else {
                        Log.d(LOG_TAG, "stream token=$token full size=$totalSize")
                        call.respondBytesWriter(
                            contentType = ContentType.Application.OctetStream,
                            status = HttpStatusCode.OK,
                            contentLength = totalSize,
                        ) {
                            pumpStream(stream, 0L, totalSize)
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) { stream.close() }
                }
            }
        }
    }

    // --- internals ---

    private suspend fun io.ktor.utils.io.ByteWriteChannel.pumpStream(
        stream: ProviderStream,
        offset: Long,
        length: Long,
    ) {
        val buf = ByteArray(PUMP_CHUNK_SIZE)
        var remaining = length
        var pos = offset
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = withContext(Dispatchers.IO) { stream.readAt(pos, buf, 0, toRead) }
            if (read == 0) break
            writeFully(buf, 0, read)
            pos += read
            remaining -= read
        }
    }

    companion object {
        /**
         * Parses `Range: bytes=<start>-[end]` and returns `(start, end)` clamped to [totalSize].
         */
        internal fun parseRange(header: String, totalSize: Long): Pair<Long, Long> {
            val spec = header.removePrefix("bytes=")
            val dashIdx = spec.indexOf('-')
            val start = if (dashIdx > 0) spec.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
            val end = if (dashIdx < spec.lastIndex) {
                spec.substring(dashIdx + 1).toLongOrNull()?.coerceAtMost(totalSize - 1) ?: (totalSize - 1)
            } else {
                totalSize - 1
            }
            return start to end
        }
    }
}
