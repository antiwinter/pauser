# JS Provider Thread Safety

This document describes the threading model used when running JavaScript providers inside the embedded QuickJS engine, the risks that arise from it, and the rules provider developers must follow.

---

## 1. Mechanism

### The jsDispatcher

Each `QuickJsEngine` instance owns a **single-threaded coroutine dispatcher**:

```kotlin
val jsDispatcher = Dispatchers.IO.limitedParallelism(1)
```

Every call that touches the QuickJS C context (`ctxPtr`) is dispatched onto this single thread. QuickJS is not thread-safe; this one thread is the sole enforcer of that invariant.

### Call flow for a provider method

```
Caller coroutine (any thread)
  │
  └─ callMethod("listEntry", argsJson)
       │
       ├─ [jsDispatcher] nativeCallMethod(ctxPtr, ...)
       │      JS starts executing synchronously until first `await`
       │      JS hits: await host.http.get(...)
       │      C calls invokeHostFunction(ns, name, args)  ← still on jsDispatcher
       │
       ├─ invokeHostFunction returns a key immediately (does NOT block)
       │      Launches coroutine on Dispatchers.IO for the HTTP call
       │
       ├─ nativeCallMethod returns  ← JS is now suspended at the Promise
       ├─ pumpJobs()                ← drains any synchronous microtasks
       ├─ Kotlin suspends: deferred.await()
       │
       │        [Dispatchers.IO — HTTP finishes]
       │
       └─ [jsDispatcher] nativeResolveHostCall(ctxPtr, key, result)
              pumpJobs()   ← JS Promise chain resumes and runs to completion
              resolveCallback(key, value) called by C
              deferred.complete(value)
       │
       └─ callMethod returns to caller
```

### Promise keying

Every in-flight call — both JS-to-Kotlin method calls and Kotlin-to-JS host dispatch calls — is tracked by a `Long` key generated from an `AtomicLong`. The key is registered in `pendingCalls: ConcurrentHashMap<Long, CompletableDeferred<String?>>` **before** the native call begins (sentinel pattern), so a fast-completing native call can never race with registration.

Keys are transmitted as `float64` through QuickJS (not `int32`) to avoid truncation for keys above 2^31.

---

## 2. Risks

### 2.1 Interleaved execution of concurrent callers on the same engine

`callMethod` suspends at `deferred.await()` after dispatching to jsDispatcher. While it is suspended, **a second coroutine can acquire jsDispatcher** and start its own `nativeCallMethod`. The two JS executions can then interleave at every `await host.*` boundary.

This is safe for JS state that is scoped to a single method call, but dangerous for **module-level mutable JS state** that two concurrent calls both read and write.

Example: a counter or cache object at module scope in `bridge.ts` mutated by two concurrent `searchItems` calls. Each will run to its next `await`, then the other advances, leaving the counter in an inconsistent state.

### 2.2 pumpJobs advancing the wrong Promise chain

`pumpJobs()` drains **all** pending microtasks in the context, not just the ones belonging to the current call. When call B resolves its host Promise and calls `pumpJobs()`, it may inadvertently advance microtasks queued by call A. In practice this is usually harmless (advancing A's chain sooner is fine), but it makes the execution order non-deterministic across concurrent calls.

Concretely: if A's Promise chain modifies module-level state and then B's continuation reads it, B may see a partially updated state.

### 2.3 invokeHostFunction must not re-enter QuickJS synchronously

`invokeHostFunction` is called **directly from C on the jsDispatcher thread** (inside `nativeCallMethod`). The method must return immediately. Any attempt to call `callMethod`, `evalSnippet`, or any `native*` function from inside `invokeHostFunction` would re-enter QuickJS on the same thread while a JS execution frame is active — undefined behaviour leading to a native crash.

### 2.4 ensureReady is not concurrency-safe

`JsProviderInstance.ensureReady()` checks the `initialized` boolean and initialises the engine if not. If two callers reach `ensureReady()` simultaneously before init completes, both will create a `QuickJsEngine`, resulting in a double-init and a leaked context.

This is only a problem if `listEntry` and `getDetail` are called concurrently on a freshly created instance before the first call finishes — i.e., a race between two coroutines on different threads both calling the same instance for the first time.

### 2.5 runBlocking in getFieldsSpec and providesCover

`JsProvider.getFieldsSpec()` uses `runBlocking` (via `runWithEngine`) to create a temporary engine synchronously. The `providesCover` property is also computed this way — it is a `by lazy` Kotlin property that calls `runWithEngine` on first access:

```kotlin
override val providesCover: Boolean by lazy {
    runWithEngine { engine ->
        engine.evalExpression("globalThis.opentuneProvider.providesCover") == "true"
    }
}
```

If either is called from a coroutine running on a limited dispatcher (e.g., the main thread), it will block that thread for the duration of engine init + bundle eval. The `providesCover` case is subtler because it looks like a plain property read at the call site, making it easy to overlook that it may block. This is a latency risk, not a correctness risk.

### 2.6 Close/destroy race

`QuickJsEngine.close()` schedules `nativeDestroyContext` on `jsDispatcher`. If a `callMethod` coroutine is mid-flight (awaiting its deferred) and `close()` is called concurrently, `nativeDestroyContext` will be queued behind the current native call — which is correct. However, once `ctxPtr` is zeroed the deferred will never be resolved, leaving the caller suspended indefinitely unless it has a timeout or cancellation.

### 2.7 JsPlaybackHooks is a concurrent caller on the same engine

`getPlaybackSpec` returns a `JsPlaybackHooks` object that holds a direct reference to the `QuickJsEngine` belonging to its `JsProviderInstance`. The player calls `onPlaybackReady`, `onProgressTick`, and `onStop` independently — each dispatches `engine.callMethod(...)` and therefore competes for `jsDispatcher` alongside any concurrent `listEntry`, `search`, or `getDetail` calls.

Two concrete scenarios:

1. A user starts playback and then navigates a browser screen at the same time. `onProgressTick` fires every 10 s while `listEntry` is fetching pages — their JS continuations can interleave at every `await host.*` boundary within the same QuickJS context.

2. If the player triggers `onStop` while an `onProgressTick` call is still awaiting a host response, the two hook invocations interleave. JS hook code that reads and writes module-level state across `await` boundaries (e.g., a running-total counter or a session refresh flag) is subject to the same corruption described in risk 2.1.

The `hooksState` JSON string is treated as immutable on the Kotlin side: it is captured once from the `getPlaybackSpec` response and passed verbatim to every hook call, never updated in place. JS hook implementations must follow the same discipline — treat `hooksState` as a read-only snapshot on entry and return a new object if state needs to change (though the Kotlin bridge currently ignores the returned value and does not thread updates back into subsequent calls). Accumulating mutable state at module scope across hook awaits is unsafe.

---

## 3. Examples That Could Lead to Errors

### 3.1 Module-level cache corrupted by concurrent searches

```typescript
// DANGEROUS — shared mutable state
const searchCache: Map<string, MediaListItem[]> = new Map();

async function searchItems(state, scope, query) {
  if (searchCache.has(query)) return searchCache.get(query)!;
  const result = await api.getItems({ searchTerm: query }); // ← suspends here
  // Another call may have inserted a different value for `query` by now
  searchCache.set(query, result.items);
  return result.items;
}
```

Two simultaneous searches for the same query both miss the cache, both fetch, and both write — whichever finishes last wins, but both callers get correct results. However, if the cache entry is a partially-built object being mutated across awaits:

```typescript
// CATASTROPHIC — mutation across an await boundary
const building: Record<string, Partial<SomeObj>> = {};

async function buildThing(id: string) {
  building[id] = {};
  building[id].part1 = await fetchPart1(id); // ← concurrent call may overwrite building[id]
  building[id].part2 = await fetchPart2(id); // ← now reading wrong object
}
```

### 3.2 nextId counter race (would be a problem if JS were multi-threaded)

```typescript
// SAFE in practice because jsDispatcher serialises JS microtasks,
// but illustrates why you must not expect atomic mutations across awaits:
let nextId = 1;

async function createInstance(args) {
  const id = nextId;       // reads 1
  await initSomething();   // suspends — another createInstance could run here...
  nextId++;                // ...and also read 1, so both get id=1
  instances.set(id, ...);
  return id;
}
```

In the current bridge this is avoided by incrementing `nextId` before any `await`. Provider developers must apply the same discipline.

### 3.3 Stale credentials after an instance update

If the JS side caches `accessToken` in a module-level variable (outside the instance map) and a `createInstance` call with refreshed credentials runs concurrently with an in-flight `resolvePlayback`, the `resolvePlayback` call may complete its first `await` and then read the already-updated token — sending the wrong token to the server.

### 3.4 Calling engine methods from inside a host handler

```kotlin
// WRONG — called inside invokeHostFunction which is on jsDispatcher
override fun handleConfig(name: String, argsJson: String): String? {
    engine.evalSnippet("...") // deadlock: jsDispatcher is busy, this call queues behind itself
    ...
}
```

This will deadlock: `evalSnippet` dispatches to `jsDispatcher`, but `jsDispatcher` is currently blocked executing the `invokeHostFunction` call. The coroutine will never make progress.

---

## 4. Rules for Provider Developers

### JS side

**Rule 1 — No mutable module-level state across await boundaries.**
Read-only constants (URL templates, codec maps, field spec arrays) are fine. Anything that is mutated and then read again after an `await` is dangerous if two calls can run concurrently on the same engine.

```typescript
// GOOD: mutation is complete before any await
async function createInstance(args) {
  const id = nextId++;          // increment atomically, no await before use
  instances.set(id, makeState(args));
  await engine.callMethod(...); // any awaits come after state is committed
  return id;
}
```

**Rule 2 — Keep per-instance state inside the instances map, not at module scope.**
Module scope is shared across all instances in the same engine. Use the `instanceId → state` map exclusively for anything instance-specific (credentials, device profile, session IDs).

**Rule 3 — Do not mutate `hooksState` in-place.**
`hooksState` is a plain JSON object passed through Kotlin and back into JS on every hook call. Treat it as immutable; return a new object if you need to update it.

**Rule 4 — Complete state transitions before the first `await` in a function.**
If a function must read-modify-write module state, do the write synchronously at the top before any `await`. Code after an `await` may interleave with another concurrent call.

**Rule 5 — Never call `host.*` outside of `async` functions.**
The `host.*` API returns Promises. Calling them synchronously (without `await`) and relying on the resolved value later creates invisible ordering dependencies that are broken by interleaving.

### Kotlin side

**Rule 6 — Do not call any `QuickJsEngine` method from inside `invokeHostFunction` or any method reachable from it.**
`invokeHostFunction` runs on `jsDispatcher` inside a native call stack. Re-entering any `native*` function from here causes undefined behaviour.

**Rule 7 — Gate `ensureReady()` with a mutex if concurrent first-calls are possible.**
The current implementation is safe as long as callers are serialised by `jsDispatcher` reaching `ensureReady()`. If that assumption changes (e.g., `ensureReady` is called before dispatching), add a `Mutex`:

```kotlin
private val initMutex = Mutex()

private suspend fun ensureReady() {
    if (initialized) return
    initMutex.withLock {
        if (initialized) return
        // ... init
    }
}
```

**Rule 8 — Apply a timeout to `callMethod` calls.**
If the engine is closed mid-flight, the deferred will never complete. Wrap calls with `withTimeout`:

```kotlin
withTimeout(30_000) { engine.callMethod("listEntry", args) }
```

---

## 5. Other Aspects Worth Noting

### Memory: one context per instance

Each `JsProviderInstance` allocates a full QuickJS heap. The bundle (~12 KB minified) is parsed and compiled separately per instance. With many servers configured this could sum to significant memory. If memory pressure becomes a concern, a shared-context pool with per-call instance switching (via `createInstance`/`destroyInstance` JS calls) could be explored, but would require careful state isolation analysis.

### No SharedArrayBuffer, no Worker

QuickJS does not support `SharedArrayBuffer` or `Worker`. Any library that expects browser multi-threading primitives will fail at runtime (caught at bundle time by the compat plugin, which blocks such imports).

### Exception propagation

JS `throw` inside a Promise chain propagates to `rejectCallback`, which calls `deferred.completeExceptionally`. The exception message is the `Error.message` string from JS — no stack trace survives the JNI boundary. Provider code should throw `new Error("descriptive message")` with enough context to diagnose failures without a stack trace.

### pumpJobs budget

`nativeExecutePendingJobs` is called with a limit of 64 jobs per iteration. A deeply chained Promise (e.g., many sequential `await`s in a single method) will be drained across multiple `pumpJobs` calls. This is not a deadlock risk — `pumpJobs` is called both after `nativeCallMethod` and after `nativeResolveHostCall`, so every host roundtrip advances the chain. A chain requiring more than 64 microtasks per host call segment will self-drain across multiple resolve calls.

### Debugging

Because the bundle is minified, QuickJS error messages contain mangled names. To debug, temporarily disable terser in `rollup.config.js` and rebuild. Source maps are not forwarded through the JNI boundary and are not useful at runtime on device.

### Version skew between JS bridge and Kotlin bridge

`bridge.ts` and `JsProviderInstance.kt` must agree on the JSON protocol for every method. If either side is updated without updating the other (e.g., adding a new field to `PlaybackSpec` in Kotlin but not in TS), the call will succeed but Kotlin's JSON deserialisation will silently ignore the field. Use `ignoreUnknownKeys = true` defensively on the Kotlin side; add fields to both sides atomically.
