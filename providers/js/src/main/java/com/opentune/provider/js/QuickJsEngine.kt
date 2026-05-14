package com.opentune.provider.js

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * QuickJS context wrapper.
 *
 * Threading contract: ALL native calls are dispatched through [jsDispatcher],
 * which is a single-threaded coroutine context. QuickJS is not thread-safe.
 *
 * Kotlin methods [resolveCallback], [rejectCallback], [invokeHostFunction] are
 * called directly from JNI (on the jsDispatcher thread) and must NOT suspend.
 */
class QuickJsEngine(
    private val hostApis: HostApis,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    /** Single-threaded dispatcher for all QuickJS JNI calls. */
    val jsDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Native context pointer — only accessed from jsDispatcher. */
    private var ctxPtr: Long = 0L

    /** Map from callback key → CompletableDeferred for in-flight JS calls. */
    private val pendingCalls = ConcurrentHashMap<Long, CompletableDeferred<String?>>()

    /** Monotonically increasing key generator. */
    private val keyGen = AtomicLong(1L)

    // ── Lifecycle ──────────────────────────────────────────────────────────

    suspend fun init() = withContext(jsDispatcher) {
        ctxPtr = nativeCreateContext()
        check(ctxPtr != 0L) { "QuickJS context creation failed" }
    }

    suspend fun evalBundle(jsCode: String) = withContext(jsDispatcher) {
        val error = nativeEvalBundle(ctxPtr, jsCode)
        check(error == null) { "Bundle eval failed: $error" }
        pumpJobs()
    }

    suspend fun evalSnippet(jsCode: String) = withContext(jsDispatcher) {
        val error = nativeEvalSnippet(ctxPtr, jsCode)
        check(error == null) { "Snippet eval failed: $error" }
        pumpJobs()
    }

    /**
     * Evaluates [jsCode] as a JS expression and returns its JSON.stringify'd value,
     * or null if the result is null/undefined. Throws if the expression throws.
     */
    suspend fun evalExpression(jsCode: String): String? = withContext(jsDispatcher) {
        nativeEvalExpression(ctxPtr, jsCode)
    }

    fun close() {
        engineScope.launch(jsDispatcher) {
            if (ctxPtr != 0L) {
                nativeDestroyContext(ctxPtr)
                ctxPtr = 0L
            }
        }
    }

    // ── Method calls ───────────────────────────────────────────────────────

    /**
     * Calls `globalThis.opentuneProvider.<method>(argsJson)` asynchronously.
     * Returns the JSON string result (or null for void methods).
     */
    suspend fun callMethod(method: String, argsJson: String): String? {
        val key = keyGen.getAndIncrement()
        val deferred = CompletableDeferred<String?>()
        // Register BEFORE calling native (sentinel pattern avoids race condition)
        pendingCalls[key] = deferred
        withContext(jsDispatcher) {
            val error = nativeCallMethod(ctxPtr, method, argsJson, key)
            if (error != null) {
                pendingCalls.remove(key)
                deferred.completeExceptionally(RuntimeException("JS call error: $error"))
                return@withContext
            }
            pumpJobs()
        }
        return deferred.await()
    }

    // ── Pump ───────────────────────────────────────────────────────────────

    /** Drains the QuickJS microtask queue. Must be called on jsDispatcher. */
    private fun pumpJobs() {
        while (nativeExecutePendingJobs(ctxPtr, 64) > 0) { /* drain */ }
    }

    // ── Callbacks called from JNI (on jsDispatcher thread) ────────────────

    @Keep
    fun resolveCallback(key: Long, value: String?) {
        val deferred = pendingCalls.remove(key) ?: run {
            Log.w(TAG, "resolveCallback: no deferred for key=$key")
            return
        }
        deferred.complete(value)
    }

    @Keep
    fun rejectCallback(key: Long, message: String) {
        val deferred = pendingCalls.remove(key) ?: run {
            Log.w(TAG, "rejectCallback: no deferred for key=$key")
            return
        }
        deferred.completeExceptionally(RuntimeException(message))
    }

    /**
     * Called from JNI when JS calls `__hostDispatch(ns, name, argsJson)`.
     * Must NOT suspend — returns a key string immediately, then schedules
     * async work that resolves the JS Promise via [nativeResolveHostCall].
     */
    @Keep
    fun invokeHostFunction(namespace: String, name: String, argsJson: String): String {
        val key = keyGen.getAndIncrement()
        engineScope.launch(Dispatchers.IO) {
            try {
                val result = dispatchHost(namespace, name, argsJson)
                withContext(jsDispatcher) {
                    val err = nativeResolveHostCall(ctxPtr, key, result)
                    if (err != null) Log.e(TAG, "resolveHostCall error: $err")
                    pumpJobs()
                }
            } catch (e: Exception) {
                val msg = e.message ?: "host error"
                withContext(jsDispatcher) {
                    val err = nativeRejectHostCall(ctxPtr, key, msg)
                    if (err != null) Log.e(TAG, "rejectHostCall error: $err")
                    pumpJobs()
                }
            }
        }
        return key.toString()
    }

    // ── Host API dispatch ──────────────────────────────────────────────────

    private suspend fun dispatchHost(ns: String, name: String, argsJson: String): String? =
        when (ns) {
            "http"     -> hostApis.handleHttp(name, argsJson, httpClient)
            "crypto"   -> hostApis.handleCrypto(name, argsJson)
            "platform" -> hostApis.handlePlatform(name, argsJson)
            else       -> throw IllegalArgumentException("Unknown host namespace: $ns")
        }

    // ── JNI externals ──────────────────────────────────────────────────────

    private external fun nativeCreateContext(): Long
    private external fun nativeDestroyContext(ctxPtr: Long)
    private external fun nativeEvalBundle(ctxPtr: Long, jsCode: String): String?
    private external fun nativeEvalSnippet(ctxPtr: Long, jsCode: String): String?
    private external fun nativeEvalExpression(ctxPtr: Long, jsCode: String): String?
    private external fun nativeExecutePendingJobs(ctxPtr: Long, maxJobs: Int): Int
    private external fun nativeCallMethod(ctxPtr: Long, method: String, argsJson: String, callbackKey: Long): String?
    private external fun nativeResolveHostCall(ctxPtr: Long, key: Long, resultJson: String?): String?
    private external fun nativeRejectHostCall(ctxPtr: Long, key: Long, errorMsg: String): String?

    companion object {
        private const val TAG = "QuickJsEngine"

        init {
            System.loadLibrary("quickjs_engine")
        }

        private fun defaultHttpClient() = OkHttpClient.Builder().build()
    }
}
