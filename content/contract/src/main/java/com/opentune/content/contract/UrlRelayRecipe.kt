package com.opentune.content.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * The recipe behind `/relay/url`. Fetches a remote URL through the endpoint's proxy client
 * (proxy on the sr→origin leg) and relays the body to the player. Forwards upstream
 * `Content-Type` / `Content-Range` / length. No cache logic — the route tees bytes into
 * `RelayCache` as they flow.
 */
class UrlRelayRecipe(
    private val resolveClient: suspend (endpointId: String) -> EndpointClient?,
) : StreamRelayRecipe {
    override suspend fun serve(params: Map<String, String>): StreamRelayResult? {
        val ep = params["ep"] ?: return null
        val originalUrl = params["url"] ?: return null
        val rangeHeader = params["Range"]
        val client = resolveClient(ep)?.proxyClient?.getHttpClient() ?: return null

        val req = Request.Builder().url(originalUrl).apply {
            if (rangeHeader != null) header("Range", rangeHeader)
        }.build()
        val response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body
        if (body == null) {
            response.close()
            return null
        }
        val contentType = body.contentType()?.toString()
        val status = response.code
        val headers = buildMap {
            response.header("Content-Range")?.let { put("Content-Range", it) }
            put("Accept-Ranges", response.header("Accept-Ranges") ?: "bytes")
        }
        val length = body.contentLength().takeIf { it > 0 }
        return StreamRelayResult(
            status = status,
            contentType = contentType,
            headers = headers,
            length = length,
            pump = { sink -> pumpBody(body, sink) },
        )
    }

    private suspend fun pumpBody(body: ResponseBody, sink: ByteSink) {
        val input = body.byteStream()
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = withContext(Dispatchers.IO) { input.read(buf) }
                if (read <= 0) break
                sink.write(buf, 0, read)
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { body.close() } }
        }
    }

    companion object {
        private val registerLock = Any()

        /** Register the `url` recipe once, lazily; epcache calls this before emitting any wrapped URL. */
        fun ensureRegistered() {
            if (StreamRelayRegistry.get("url") != null) return
            synchronized(registerLock) {
                if (StreamRelayRegistry.get("url") != null) return
                StreamRelayRegistry.register("url", UrlRelayRecipe { ep ->
                    runCatching { EndpointClientRegistryHolder.get().getOrCreate(ep) }.getOrNull()
                })
            }
        }
    }
}
