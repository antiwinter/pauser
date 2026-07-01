package com.opentune.provider.js

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Result of a successful sniff: the matched resource URL and the request headers
 * the WebView attached to it.
 */
data class SniffMatch(val url: String, val headers: Map<String, String>)

/**
 * Headless-WebView resource sniffer.
 *
 * Loads a page in an off-screen [WebView], watches every sub-resource request via
 * [WebViewClient.shouldInterceptRequest], and resolves with the first request whose
 * URL matches one of [regex] but none of [exclude]. Optional [script] snippets are
 * injected after the page finishes loading (e.g. to click a play button).
 *
 * All WebView access happens on the main thread. The instance is single-flight: a
 * [Mutex] serialises calls so we never hold more than one live WebView at a time.
 */
class WebSniffer {

    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun detect(
        url: String,
        headers: Map<String, String>,
        regex: List<String>,
        exclude: List<String>,
        script: List<String>,
        timeoutMs: Long,
    ): SniffMatch? = mutex.withLock {
        val patterns = regex.map { Regex(it) }
        val excludes = exclude.map { Regex(it) }
        val matched = CompletableDeferred<SniffMatch>()

        // WebView must be created, driven, and destroyed on the main thread.
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    webView = buildWebView(matched, patterns, excludes, script).also {
                        if (headers.isEmpty()) it.loadUrl(url)
                        else it.loadUrl(url, headers)
                    }
                    matched.await()
                }
            } catch (e: Throwable) {
                Timber.w(e, "web.detect failed for %s", url)
                null
            } finally {
                webView?.let { wv ->
                    wv.stopLoading()
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        matched: CompletableDeferred<SniffMatch>,
        patterns: List<Regex>,
        excludes: List<Regex>,
        script: List<String>,
    ): WebView {
        val context = ContextHolder.get()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val candidate = request.url?.toString()
                    if (candidate != null && !matched.isCompleted) {
                        val hit = patterns.any { it.containsMatchIn(candidate) } &&
                            excludes.none { it.containsMatchIn(candidate) }
                        if (hit) {
                            matched.complete(SniffMatch(candidate, request.requestHeaders ?: emptyMap()))
                        }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    for (snippet in script) {
                        view.evaluateJavascript(snippet, null)
                    }
                }
            }
        }
    }
}
