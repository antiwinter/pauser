---
name: Eliminate JS Thread Safety Risks
overview: Refactor QuickJsEngine to a Channel-based event loop (architectural cleanup + fixes 2.2, 2.6), add initMutex to ensureReady (fixes 2.4), and eagerly compute providesCover/fieldsSpec in the JsProvider constructor (fixes 2.5).
todos:
  - id: eventloop-refactor
    content: Replace jsDispatcher + scattered withContext/pumpJobs in QuickJsEngine with a Channel task queue and a single event loop coroutine
    status: completed
  - id: initmutex
    content: Add initMutex with double-checked locking to JsProviderInstance.ensureReady()
    status: completed
  - id: eager-init
    content: Replace providesCover by lazy and getFieldsSpec runWithEngine with eager init{} in JsProvider constructor
    status: completed
isProject: false
---

# Eliminate JS Provider Thread-Safety Risks

Three Kotlin files change. No JNI, contract, or TypeScript changes are needed.

Risks 2.1, 2.2 (from the provider-author perspective), and 2.7 (concurrent-call JS interleaving) are **intentionally not serialized**. The event loop preserves interleaving — multiple calls can be in flight simultaneously, with continuations interleaved across tasks. This matches the Node.js/browser async contract. Rules 1–5 in the research doc document the provider author's responsibility.

---

## 1. `QuickJsEngine.kt` — event loop refactor (fixes 2.2 structurally, 2.6)

**File:** [`providers/js/src/main/java/com/opentune/provider/js/QuickJsEngine.kt`](providers/js/src/main/java/com/opentune/provider/js/QuickJsEngine.kt)

### What changes

**Removed:**
- `val jsDispatcher` field — no longer exposed or needed externally
- `var ctxPtr: Long` field — moves to a local variable inside the engine coroutine
- `withContext(jsDispatcher) { ... }` blocks in every public method
- `pumpJobs()` calls inside `invokeHostFunction`'s coroutine and inside `callMethod`

**Added:**
- `private val taskChannel = Channel<EngineTask>(Channel.UNLIMITED)` — the single input queue
- `private sealed class EngineTask` with variants for every engine operation
- A single engine coroutine started by `init()` that loops over the channel

### Sealed task type

```kotlin
private sealed class EngineTask {
    data class CallMethod(val method: String, val args: String, val key: Long) : EngineTask()
    data class ResolveHost(val hostKey: Long, val result: String?) : EngineTask()
    data class RejectHost(val hostKey: Long, val error: String) : EngineTask()
    data class EvalSnippet(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
    data class EvalBundle(val code: String, val deferred: CompletableDeferred<Unit>) : EngineTask()
    data class EvalExpression(val code: String, val deferred: CompletableDeferred<String?>) : EngineTask()
}
```

### Engine loop (started by `init()`)

`ctxPtr` is a local variable — it never escapes the coroutine. `pumpJobs` is called in exactly one place: after each task.

```kotlin
suspend fun init() {
    val ready = CompletableDeferred<Unit>()
    engineScope.launch(Dispatchers.IO.limitedParallelism(1)) {
        val ctx = nativeCreateContext()
        if (ctx == 0L) {
            ready.completeExceptionally(RuntimeException("QuickJS context creation failed"))
            return@launch
        }
        ready.complete(Unit)
        for (task in taskChannel) {        // suspends when empty, exits when channel is closed
            processTask(ctx, task)
            pumpJobs(ctx)
        }
        // Channel closed — clean up
        nativeDestroyContext(ctx)
        val err = CancellationException("QuickJsEngine closed")
        pendingCalls.values.forEach { it.completeExceptionally(err) }
        pendingCalls.clear()
    }
    ready.await()
}
```

### Public API — enqueue and await

Every public call becomes: create deferred → send task → return `deferred.await()`.

```kotlin
suspend fun callMethod(method: String, argsJson: String): String? {
    val key = keyGen.getAndIncrement()
    val deferred = CompletableDeferred<String?>()
    pendingCalls[key] = deferred               // sentinel: register before enqueue
    taskChannel.send(EngineTask.CallMethod(method, argsJson, key))
    return deferred.await()
}

suspend fun evalSnippet(jsCode: String) {
    val deferred = CompletableDeferred<Unit>()
    taskChannel.send(EngineTask.EvalSnippet(jsCode, deferred))
    deferred.await()
}
// evalBundle and evalExpression follow the same pattern
```

### `invokeHostFunction` — enqueue resolve/reject, no withContext

`invokeHostFunction` is called from C on the engine thread while the loop is processing a task. It must return immediately. It launches an IO coroutine for the host work, then enqueues the result with `trySend` — which never blocks on an `UNLIMITED` channel.

```kotlin
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
```

### `close()` — channel close triggers graceful exit

```kotlin
fun close() {
    taskChannel.close()   // loop drains remaining tasks then exits; cleanup runs in the coroutine
}
```

### `processTask` — all native calls in one place

```kotlin
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

private fun pumpJobs(ctx: Long) {
    while (nativeExecutePendingJobs(ctx, 64) > 0) { /* drain */ }
}
```

`resolveCallback` and `rejectCallback` (called from C during `pumpJobs`) remain unchanged — they only touch `pendingCalls`, which is still a field.

---

## 2. `JsProviderInstance.kt` — initMutex (fixes 2.4)

**File:** [`providers/js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt`](providers/js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt)

Add a `Mutex` with double-checked locking so two coroutines racing on the first call cannot both allocate a `QuickJsEngine`:

```kotlin
private val initMutex = Mutex()

private suspend fun ensureReady() {
    if (initialized) return
    initMutex.withLock {
        if (initialized) return
        engine = QuickJsEngine(hostApis)
        engine.init()
        // ... rest of init ...
        initialized = true
    }
}
```

---

## 3. `JsProvider.kt` — eager init (fixes 2.5)

**File:** [`providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt`](providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt)

The root problem is `by lazy` — it defers the blocking `runWithEngine` to an unpredictable first-access time that may be on the composition thread. Fix: compute both values eagerly in the constructor. `JsProvider` is constructed inside `JsProviderLoader.load()` → `OpenTuneProviderRegistry.discover()` → `Application.onCreate()`. Blocking there is expected and acceptable.

```kotlin
override val providesCover: Boolean
private val cachedFieldsSpec: List<ServerFieldSpec>

init {
    runWithEngine { engine ->
        providesCover = engine.evalExpression("globalThis.opentuneProvider.providesCover") == "true"
        val result = engine.callMethod("getFieldsSpec", "{}") ?: ""
        cachedFieldsSpec = parseFieldsSpec(result)
    }
}

override fun getFieldsSpec(): List<ServerFieldSpec> = cachedFieldsSpec
```

---

## Risk coverage table

- Resolved = fix eliminates the condition
- Partial = risk reduced but not fully eliminated
- No = no code fix; convention/doc only
- Out of scope = provider-author responsibility, same as Node.js/browser async contract

| Risk | Description | Fix | Status |
| ---- | ----------- | --- | ------ |
| 2.1 | Interleaved JS execution across concurrent callers | Provider author rule (Rules 1–5) | Out of scope |
| 2.2 | `pumpJobs` advancing the wrong Promise chain | Event loop: single drain point per task | Structurally resolved |
| 2.3 | `invokeHostFunction` re-entering QuickJS synchronously | Cannot be enforced at compile time | No — convention only |
| 2.4 | `ensureReady` double-init race | `initMutex` with double-check | Resolved |
| 2.5 | `runBlocking` on limited dispatcher from lazy/per-call path | Eager `init {}` in `JsProvider` constructor | Partial — blocking moves to `onCreate`, not eliminated |
| 2.6 | Close/destroy leaves callers suspended indefinitely | Loop exit completes `pendingCalls` exceptionally | Resolved |
| 2.7 | `JsPlaybackHooks` as concurrent engine caller | Provider author rule (same as 2.1) | Out of scope |
