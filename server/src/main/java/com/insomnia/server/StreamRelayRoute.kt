package com.insomnia.server

import com.insomnia.content.contract.ByteSink
import com.insomnia.content.contract.StreamRelayRegistry
import com.insomnia.content.contract.parseRange
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

            val playerRange = params["Range"]
            val cached = params["cached"] == "true"
            Timber.d("sr: req range=${playerRange ?: "none"} cached=$cached")

            val key = buildString {
                append("/relay/").append(token)
                val qs = call.parameters.names().filter { it != "token" }.sorted()
                    .joinToString("&") { "$it=${call.parameters[it]}" }
                if (qs.isNotEmpty()) append('?').append(qs)
            }

            if (cached) {
                RelayCache.beginRequest(key)
                val meta = RelayCache.meta()
                val rangeHeader = params["Range"]
                if (meta != null && meta.totalSize > 0) {
                    val (start, rangeEnd) = if (rangeHeader != null) parseRange(rangeHeader, meta.totalSize)
                        else 0L to (meta.totalSize - 1)
                    val cachedEnd = RelayCache.getCachedContiguousEnd(start, rangeEnd)

                    val canServe = if (rangeHeader == null) (cachedEnd == rangeEnd) else (cachedEnd != null)

                    if (canServe && cachedEnd != null) {
                        val length = cachedEnd - start + 1
                        val cr = "bytes $start-$cachedEnd/${meta.totalSize}"
                        Timber.d("sr: cache hit [$start-$cachedEnd] length=$length")
                        try {
                            call.response.status(
                                if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                            )
                            if (rangeHeader != null) {
                                call.response.header("Content-Range", cr)
                            }
                            call.response.header("Accept-Ranges", "bytes")
                            val ct = meta.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                                ?: ContentType.Application.OctetStream
                            call.respondBytesWriter(contentType = ct, contentLength = length) {
                                RelayCache.tryServe(start, cachedEnd, sinkAdapter())
                            }
                        } catch (e: Throwable) {
                            if (isBrokenPipe(e)) {
                                Timber.d("sr: cache serve disconnected by client")
                            } else {
                                Timber.e(e, "sr: cache serve failed")
                            }
                        }
                        Timber.d("sr: resp → player status=${if (rangeHeader != null) 206 else 200} cr=$cr length=$length source=cache")
                        return@get
                    }

                    if (rangeHeader != null) {
                        val nextStart = RelayCache.getNextCachedStart(start)
                        if (nextStart != null && nextStart <= rangeEnd) {
                            val newEnd = nextStart - 1
                            params["Range"] = "bytes=$start-$newEnd"
                            Timber.d("sr: cache miss at $start, split remote to [$start-$newEnd] (cache ahead at $nextStart)")
                        } else {
                            Timber.d("sr: cache miss at $start, no cache ahead")
                        }
                    }
                }
            }

            val remoteRange = params["Range"]
            Timber.d("sr: → remote range=${remoteRange ?: "none"}")
            val result = withContext(Dispatchers.IO) { recipe.serve(params) }
            if (result == null) {
                Timber.d("sr: remote returned null")
                call.respondBytes("relay returned no content".toByteArray(), status = HttpStatusCode.NotFound)
                return@get
            }

            if (cached) {
                val cr = result.headers["Content-Range"]
                val totalSize = parseContentRangeTotal(cr)
                    ?: (if (result.status == 200) result.length else null)
                if (totalSize != null) RelayCache.storeMeta(totalSize, result.contentType)
            }

            val teeEnabled = cached && !(params["Range"] != null && result.status == 200)
            val startPos = if (result.status == 206) {
                parseContentRangeStart(result.headers["Content-Range"]) ?: 0L
            } else 0L
            Timber.d("sr: ← remote status=${result.status} cr=${result.headers["Content-Range"]} tee=$teeEnabled startPos=$startPos")

            try {
                call.response.status(HttpStatusCode.fromValue(result.status))
                for ((k, v) in result.headers) call.response.header(k, v)
                val ct = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                call.respondBytesWriter(contentType = ct, contentLength = result.length) {
                    result.pump(teeingSink(startPos, sinkAdapter(), teeEnabled))
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

    /** Forwards to [downstream] and, when [enabled], captures each chunk into [RelayCache] at [startPos]+. */
    private fun teeingSink(startPos: Long, downstream: ByteSink, enabled: Boolean): ByteSink {
        if (!enabled) return downstream
        var pos = startPos
        var totalWritten = 0L
        return ByteSink { buf, off, len ->
            downstream.write(buf, off, len)
            RelayCache.put(pos, buf, off, len)
            pos += len
            totalWritten += len
        }
    }

    private fun parseContentRangeStart(header: String?): Long? {
        val spec = header?.removePrefix("bytes=")?.removePrefix("bytes ")?.trim() ?: return null
        val dash = spec.indexOf('-')
        if (dash <= 0) return null
        return spec.substring(0, dash).toLongOrNull()
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        val slash = header?.indexOf('/') ?: return null
        if (slash < 0) return null
        val t = header.substring(slash + 1).trim()
        return if (t == "*") null else t.toLongOrNull()
    }
}
