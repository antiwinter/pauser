package com.insomnia.provider.js

import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * QuickJS context wrapper — one engine coroutine owns the context.
 * [resolveCallback]/[rejectCallback] are called from C on the engine thread
 * and must NOT suspend. [invokeHostFunction] returns immediately; it enqueues
 * `SettleHost` via [Channel.trySend], which never blocks on an UNLIMITED channel.
 */
class QuickJsEngine(
    private val hostApis: HostApis,
    private val httpClient: OkHttpClient,
    notificationDispatcher: suspend (method: String, result: JsonObject?) -> Unit,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jarLoader   = JarLoader(hostApis.sandboxRoot, httpClient)
    private val engineHostApis = EngineHostApis(httpClient, jarLoader, notificationDispatcher)

    /** Single input queue. UNLIMITED so trySend from invokeHostFunction never blocks. */
    private val taskChannel = Channel<EngineTask>(Channel.UNLIMITED)

    private val pendingCalls = ConcurrentHashMap<Long, CompletableDeferred<String?>>()

    private val keyGen = AtomicLong(1L)

    /** In-flight OkHttp calls issued by this engine's sync/async http host handlers.
     *  [abortInFlightHttp] cancels them so a stalled [callMethod] (e.g. a long search)
     *  unblocks the single-threaded engine for the next call. */
    private val inFlightHttp = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    fun abortInFlightHttp() {
        inFlightHttp.forEach { runCatching { it.cancel() } }
    }

    private sealed class EngineTask {
        data class CallMethod(val method: String, val args: String, val key: Long) : EngineTask()
        data class SettleHost(val hostKey: Long, val result: String?, val isError: Boolean) : EngineTask()
        data class EvalSnippet(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
        data class EvalBundle(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
        data class EvalExpression(val code: String, val deferred: CompletableDeferred<String?>) : EngineTask()
    }

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

    /** Calls `globalThis.insomniaProvider.<method>(argsJson)`; returns JSON result or null. */
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

    /** Returns `JSON.stringify`'d result, or null if the expression is null/undefined. */
    suspend fun evalExpression(jsCode: String): String? {
        val deferred = CompletableDeferred<String?>()
        taskChannel.send(EngineTask.EvalExpression(jsCode, deferred))
        return deferred.await()
    }

    private fun processTask(ctx: Long, task: EngineTask) {
        when (task) {
            is EngineTask.CallMethod -> {
                val error = nativeCallMethod(ctx, task.method, task.args, task.key)
                if (error != null) {
                    pendingCalls.remove(task.key)
                        ?.completeExceptionally(RuntimeException("JS call error: $error"))
                }
            }
            is EngineTask.SettleHost -> {
                val err = nativeSettleHostCall(ctx, task.hostKey, task.result, task.isError)
                if (err != null) Timber.e("settleHostCall error: $err")
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

    private fun pumpJobs(ctx: Long) {
        while (nativeExecutePendingJobs(ctx, 64) > 0) { /* drain */ }
    }

    // Called from JNI on the engine thread during pumpJobs

    @Keep
    fun resolveCallback(key: Long, value: String?) {
        val deferred = pendingCalls.remove(key) ?: run {
            Timber.w("resolveCallback: no deferred for key=$key")
            return
        }
        deferred.complete(value)
    }

    @Keep
    fun rejectCallback(key: Long, message: String) {
        val deferred = pendingCalls.remove(key) ?: run {
            Timber.w("rejectCallback: no deferred for key=$key")
            return
        }
        deferred.completeExceptionally(RuntimeException(message))
    }

    /** Called from JNI when JS calls `__hostDispatch`. Must NOT suspend — returns a key immediately; the IO coroutine enqueues `SettleHost` on completion. */
    @Keep
    fun invokeHostFunction(namespace: String, name: String, argsJson: String): String {
        val key = keyGen.getAndIncrement()
        engineScope.launch(Dispatchers.IO) {
            try {
                val result = dispatchHost(namespace, name, argsJson)
                taskChannel.trySend(EngineTask.SettleHost(key, result, false))
            } catch (e: Throwable) {
                Timber.e(e, "host async failed: $namespace.$name")
                taskChannel.trySend(EngineTask.SettleHost(key, e.message ?: "host error", true))
            }
        }
        return key.toString()
    }

    private suspend fun dispatchHost(ns: String, name: String, argsJson: String): String? =
        when (ns) {
            // Engine-scoped (per-endpoint state: this engine's client/loader/sniffer)
            "dns"    -> engineHostApis.handleDns(name, argsJson)
            "relay"  -> engineHostApis.handleRelay(name, argsJson)
            "web"    -> engineHostApis.handleWeb(name, argsJson)
            "notification" -> engineHostApis.handleNotification(name, argsJson)
            // Shared stateless handlers
            "http"   -> hostApis.handleHttp(name, argsJson, httpClient, inFlightHttp)
            "crypto" -> hostApis.handleCrypto(name, argsJson)
            "jar"    -> hostApis.handleJar(name, argsJson, jarLoader)
            "fs"     -> hostApis.handleFs(name, argsJson)
            "log"    -> hostApis.handleLog(name, argsJson)
            "timer"  -> hostApis.handleTimer(name, argsJson)
            else     -> throw IllegalArgumentException("Unknown host namespace: $ns")
        }

    /** Called synchronously from C; runs on the engine thread — blocks it for the duration. */
    @Keep
    fun invokeHostFunctionSync(namespace: String, name: String, argsJson: String): String? =
        when (namespace) {
            "http" -> hostApis.handleHttpSync(name, argsJson, httpClient, inFlightHttp)
            else   -> throw IllegalArgumentException("No sync handler for namespace: $namespace")
        }

    private external fun nativeCreateContext(): Long
    private external fun nativeDestroyContext(ctxPtr: Long)
    private external fun nativeEvalBundle(ctxPtr: Long, jsCode: String): String?
    private external fun nativeEvalSnippet(ctxPtr: Long, jsCode: String): String?
    private external fun nativeEvalExpression(ctxPtr: Long, jsCode: String): String?
    private external fun nativeExecutePendingJobs(ctxPtr: Long, maxJobs: Int): Int
    private external fun nativeCallMethod(ctxPtr: Long, method: String, argsJson: String, callbackKey: Long): String?
    private external fun nativeSettleHostCall(ctxPtr: Long, key: Long, payload: String?, isError: Boolean): String?

    companion object {
        init {
            System.loadLibrary("quickjs_engine")
        }
    }
}
