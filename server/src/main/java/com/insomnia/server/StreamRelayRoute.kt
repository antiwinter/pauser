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

            // fongmi merges params + headers into one Map (process/Proxy.java:25-27)
            val params = HashMap<String, String>()
            for (name in call.parameters.names()) call.parameters[name]?.let { params[name] = it }
            for (name in call.request.headers.names()) call.request.headers[name]?.let { params[name] = it }

            val cached = params["cached"] == "true"
            // Key = path + query params (sorted, stable). Range is a header, so seeks on the
            // same source share a key; the route param `token` is excluded.
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
                    val (start, end) = if (rangeHeader != null) parseRange(rangeHeader, meta.totalSize)
                        else 0L to (meta.totalSize - 1)
                    if (RelayCache.covers(start, end)) {
                        try {
                            call.response.status(
                                if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                            )
                            if (rangeHeader != null) {
                                call.response.header("Content-Range", "bytes $start-$end/${meta.totalSize}")
                            }
                            call.response.header("Accept-Ranges", "bytes")
                            val ct = meta.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                                ?: ContentType.Application.OctetStream
                            call.respondBytesWriter(contentType = ct, contentLength = end - start + 1) {
                                RelayCache.tryServe(start, end, sinkAdapter())
                            }
                        } catch (e: Throwable) {
                            Timber.e(e, "relay cache serve failed token=$token")
                        }
                        return@get
                    }
                }
            }

            val result = withContext(Dispatchers.IO) { recipe.serve(params) }
            if (result == null) {
                call.respondBytes("relay returned no content".toByteArray(), status = HttpStatusCode.NotFound)
                return@get
            }

            if (cached) {
                val cr = result.headers["Content-Range"]
                val totalSize = parseContentRangeTotal(cr)
                    ?: (if (result.status == 200) result.length else null)
                if (totalSize != null) RelayCache.storeMeta(totalSize, result.contentType)
            }

            // Skip the tee when the upstream ignored Range (200 to a Range request) — can't align
            // a whole-file response to the requested range for caching.
            val teeEnabled = cached && !(params["Range"] != null && result.status == 200)
            val startPos = if (result.status == 206) {
                parseContentRangeStart(result.headers["Content-Range"]) ?: 0L
            } else 0L

            try {
                call.response.status(HttpStatusCode.fromValue(result.status))
                for ((k, v) in result.headers) call.response.header(k, v)
                val ct = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                call.respondBytesWriter(contentType = ct, contentLength = result.length) {
                    result.pump(teeingSink(startPos, sinkAdapter(), teeEnabled))
                }
            } catch (e: Throwable) {
                Timber.e(e, "relay route stream failed token=$token")
            }
        }
    }

    private fun ByteWriteChannel.sinkAdapter(): ByteSink =
        ByteSink { buf, off, len -> writeFully(buf, off, len) }

    /** Forwards to [downstream] and, when [enabled], captures each chunk into [RelayCache] at [startPos]+. */
    private fun teeingSink(startPos: Long, downstream: ByteSink, enabled: Boolean): ByteSink {
        if (!enabled) return downstream
        var pos = startPos
        return ByteSink { buf, off, len ->
            downstream.write(buf, off, len)
            RelayCache.put(pos, buf, off, len)
            pos += len
        }
    }

    private fun parseContentRangeStart(header: String?): Long? {
        val spec = header?.removePrefix("bytes=")?.trim() ?: return null
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
