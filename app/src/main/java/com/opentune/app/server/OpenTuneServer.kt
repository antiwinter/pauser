package com.opentune.app.server

import android.util.Log
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.ProviderStream
import com.opentune.provider.StreamRegistrar
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "OpenTuneServer"
private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * Embedded HTTP server started at app launch.
 * Binds to all interfaces (`0.0.0.0`) on an ephemeral port so both local player
 * and LAN clients can reach it.
 *
 * Implements [StreamRegistrar]: providers call [registerStream] to obtain a
 * `http://127.0.0.1:<port>/stream/<token>` URL and later [revokeToken] to clean up.
 */
class OpenTuneServer : StreamRegistrar {

    private data class TokenEntry(
        val instance: OpenTuneProviderInstance,
        val itemRef: String,
    )

    private val registry = ConcurrentHashMap<String, TokenEntry>()

    val port: Int = findFreePort()

    private val engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
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

    fun start() {
        engine.start(wait = false)
        Log.i(LOG_TAG, "OpenTuneServer started on port $port")
    }

    fun stop() {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    // --- StreamRegistrar ---

    override fun registerStream(instance: OpenTuneProviderInstance, itemRef: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        registry[token] = TokenEntry(instance, itemRef)
        return "http://127.0.0.1:$port/stream/$token"
    }

    override fun revokeToken(url: String) {
        val token = url.substringAfterLast('/')
        registry.remove(token)
        Log.d(LOG_TAG, "revoked token=$token registry.size=${registry.size}")
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
        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Parses `Range: bytes=<start>-[end]` and returns `(start, end)` clamped to [totalSize].
         * Handles open-ended ranges (`bytes=512-`).
         */
        private fun parseRange(header: String, totalSize: Long): Pair<Long, Long> {
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
