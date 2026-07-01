package com.opentune.server

import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.ProviderStream
import com.opentune.content.contract.SERVER_PORT
import com.opentune.content.contract.StreamRegistrar
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
import timber.log.Timber

private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * Token-based stream proxy that bridges provider byte-streams to plain HTTP URLs.
 *
 * One SMB session is opened per HTTP request and closed when the response finishes.
 * File size is cached after the first query so repeated HEAD-like requests don't
 * open extra connections.
 */
class StreamProxy : StreamRegistrar {

    private data class TokenEntry(
        val instance: EndpointClient,
        val itemRef: String,
        @Volatile var cachedSize: Long = -1L,
    )

    private val registry = ConcurrentHashMap<String, TokenEntry>()

    override fun registerStream(instance: EndpointClient, itemRef: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        registry[token] = TokenEntry(instance, itemRef)
        return "http://127.0.0.1:${SERVER_PORT}/stream/$token"
    }

    override fun revokeToken(url: String) {
        val token = url.substringAfterLast('/')
        registry.remove(token)
        Timber.d( "revoked token=$token registry.size=${registry.size}")
    }

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
                    val totalSize = withContext(Dispatchers.IO) {
                        if (entry.cachedSize < 0) entry.cachedSize = stream.getSize()
                        entry.cachedSize
                    }

                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")

                    if (rangeHeader != null) {
                        val (start, end) = parseRange(rangeHeader, totalSize)
                        val length = end - start + 1
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                        Timber.d( "stream token=$token range=$start-$end/$totalSize")
                        call.respondBytesWriter(
                            contentType = ContentType.Application.OctetStream,
                            status = HttpStatusCode.PartialContent,
                            contentLength = length,
                        ) { pump(stream, start, length) }
                    } else {
                        Timber.d( "stream token=$token full size=$totalSize")
                        call.respondBytesWriter(
                            contentType = ContentType.Application.OctetStream,
                            status = HttpStatusCode.OK,
                            contentLength = totalSize,
                        ) { pump(stream, 0L, totalSize) }
                    }
                } finally {
                    withContext(Dispatchers.IO) { stream.close() }
                }
            }
        }
    }

    private suspend fun io.ktor.utils.io.ByteWriteChannel.pump(
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
            if (read <= 0) break
            writeFully(buf, 0, read)
            pos += read
            remaining -= read
        }
    }

    companion object {
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
