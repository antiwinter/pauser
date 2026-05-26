package com.opentune.provider.js

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches a remote JS URL and evals it into a QuickJsEngine, with session-level caching.
 *
 * Designed for drpy2 asset loading (cat.js, crypto-js.js, etc.) — subsequent calls
 * with the same URL are no-ops when cache=true.
 */
class EvalLoader(private val httpClient: OkHttpClient) {

    private val evaled = ConcurrentHashMap<String, Boolean>()

    suspend fun evalScript(url: String, cache: Boolean, engine: QuickJsEngine) {
        if (cache && evaled.containsKey(url)) return
        val js = httpClient.newCall(Request.Builder().url(url).build())
            .execute().use { r ->
                check(r.isSuccessful) { "Script fetch failed: HTTP ${r.code} $url" }
                r.body!!.string()
            }
        engine.evalSnippet(js)
        if (cache) evaled[url] = true
    }

    fun clear() = evaled.clear()
}
