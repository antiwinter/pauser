package com.opentune.provider.js

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * QuickJS context wrapper.
 *
 * Runs a single engine coroutine that owns the QuickJS context pointer. All JS
 * execution happens on that coroutine; callers enqueue [EngineTask]s via
 * [taskChannel] and await results through [CompletableDeferred].
 *
 * Threading contract:
 *  - [taskChannel] is the only input path to the engine thread.
 *  - [resolveCallback] and [rejectCallback] are called from C on the engine
 *    thread (during [pumpJobs]) and must NOT suspend.
 *  - [invokeHostFunction] is called from C on the engine thread and must
 *    return immediately; it enqueues [EngineTask.ResolveHost] / [EngineTask.RejectHost]
 *    via [Channel.trySend], which never blocks on an UNLIMITED channel.
 */
class QuickJsEngine(
    private val hostApis: HostApis,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single input queue. UNLIMITED so trySend from invokeHostFunction never blocks. */
    private val taskChannel = Channel<EngineTask>(Channel.UNLIMITED)

    /** Map from callback key → CompletableDeferred for in-flight JS calls. */
    private val pendingCalls = ConcurrentHashMap<Long, CompletableDeferred<String?>>()

    /** Monotonically increasing key generator. */
    private val keyGen = AtomicLong(1L)

    // ── Task type ──────────────────────────────────────────────────────────

    private sealed class EngineTask {
        data class CallMethod(val method: String, val args: String, val key: Long) : EngineTask()
        data class ResolveHost(val hostKey: Long, val result: String?) : EngineTask()
        data class RejectHost(val hostKey: Long, val error: String) : EngineTask()
        data class EvalSnippet(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
        data class EvalBundle(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
        data class EvalExpression(val code: String, val deferred: CompletableDeferred<String?>) : EngineTask()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Creates the QuickJS context and starts the engine loop coroutine.
     * Must be called before any other method.
     */
    suspend fun init() {
        val ready = CompletableDeferred<Unit>()
        engineScope.launch(Dispatchers.IO.limitedParallelism(1)) {
            val ctx = nativeCreateContext()
            if (ctx == 0L) {
                ready.completeExceptionally(RuntimeException("QuickJS context creation failed"))
                return@launch
            }
            ready.complete(Unit)
            // Engine loop: one task at a time, pumpJobs after each.
            for (task in taskChannel) {
                processTask(ctx, task)
                pumpJobs(ctx)
            }
            // Channel closed — destroy context and unblock any waiting callers.
            nativeDestroyContext(ctx)
            val err = CancellationException("QuickJsEngine closed")
            pendingCalls.values.forEach { it.completeExceptionally(err) }
            pendingCalls.clear()
        }
        ready.await()
    }

    /** Closes the task channel, causing the engine loop to drain and exit. */
    fun close() {
        taskChannel.close()
    }

    // ── Public API — enqueue and await ─────────────────────────────────────

    /**
     * Calls `globalThis.opentuneProvider.<method>(argsJson)` asynchronously.
     * Returns the JSON string result (or null for void methods).
     */
    suspend fun callMethod(method: String, argsJson: String): String? {
        val key = keyGen.getAndIncrement()
        val deferred = CompletableDeferred<String?>()
        // Register BEFORE enqueuing (sentinel pattern: fast-completing native call
        // cannot race with registration).
        pendingCalls[key] = deferred
        try {
            taskChannel.send(EngineTask.CallMethod(method, argsJson, key))
        } catch (e: Exception) {
            pendingCalls.remove(key)
            throw e
        }
        return deferred.await()
    }

    suspend fun evalBundle(jsCode: String) {
        val deferred = CompletableDeferred<Unit>()
        taskChannel.send(EngineTask.EvalBundle(jsCode, deferred))
        deferred.await()
    }

    suspend fun evalSnippet(jsCode: String) {
        val deferred = CompletableDeferred<Unit>()
        taskChannel.send(EngineTask.EvalSnippet(jsCode, deferred))
        deferred.await()
    }

    /**
     * Evaluates [jsCode] as a JS expression and returns its JSON.stringify'd value,
     * or null if the result is null/undefined.
     */
    suspend fun evalExpression(jsCode: String): String? {
        val deferred = CompletableDeferred<String?>()
        taskChannel.send(EngineTask.EvalExpression(jsCode, deferred))
        return deferred.await()
    }

    // ── Engine loop helpers ────────────────────────────────────────────────

    private fun processTask(ctx: Long, task: EngineTask) {
        when (task) {
            is EngineTask.CallMethod -> {
                val error = nativeCallMethod(ctx, task.method, task.args, task.key)
                if (error != null) {
                    pendingCalls.remove(task.key)
                        ?.completeExceptionally(RuntimeException("JS call error: $error"))
                }
            }
            is EngineTask.ResolveHost -> {
                val err = nativeResolveHostCall(ctx, task.hostKey, task.result)
                if (err != null) Log.e(TAG, "resolveHostCall error: $err")
            }
            is EngineTask.RejectHost -> {
                val err = nativeRejectHostCall(ctx, task.hostKey, task.error)
                if (err != null) Log.e(TAG, "rejectHostCall error: $err")
            }
            is EngineTask.EvalSnippet -> {
                val error = nativeEvalSnippet(ctx, task.code)
                if (error != null) task.deferred.completeExceptionally(RuntimeException(error))
                else task.deferred.complete(Unit)
            }
            is EngineTask.EvalBundle -> {
                val error = nativeEvalBundle(ctx, task.code)
                if (error != null) task.deferred.completeExceptionally(RuntimeException(error))
                else task.deferred.complete(Unit)
            }
            is EngineTask.EvalExpression -> {
                task.deferred.complete(nativeEvalExpression(ctx, task.code))
            }
        }
    }

    /** Drains the QuickJS microtask queue. Called once per task in the engine loop. */
    private fun pumpJobs(ctx: Long) {
        while (nativeExecutePendingJobs(ctx, 64) > 0) { /* drain */ }
    }

    // ── Callbacks called from JNI (on engine thread, during pumpJobs) ──────

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
     * Must NOT suspend — returns a key string immediately, then launches an
     * IO coroutine that enqueues a [EngineTask.ResolveHost] or [EngineTask.RejectHost]
     * when the host work completes.
     */
    @Keep
    fun invokeHostFunction(namespace: String, name: String, argsJson: String): String {
        val key = keyGen.getAndIncrement()
        engineScope.launch(Dispatchers.IO) {
            try {
                val result = dispatchHost(namespace, name, argsJson)
                taskChannel.trySend(EngineTask.ResolveHost(key, result))
            } catch (e: Exception) {
                taskChannel.trySend(EngineTask.RejectHost(key, e.message ?: "host error"))
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
