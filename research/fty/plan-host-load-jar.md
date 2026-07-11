# Plan: `host.jar` — Dynamic JAR Loading for Insomnia JS Provider

> **Superseded by `.claude/plans/jar-loader-agnostic-split.md`.** This is the original research doc that motivated the design. The API shapes below (`host.jar.load({ url, md5 })`) are pre-refactor. The active contract is in `providers-ts/utils/types.ts` — see `host.jar.load({ source: { url | path | buffer } })`, `host.crypto.checksum({ input, algo, encoding? })`, and the generic primitives `loadClass` / `registerLoader` / `adoptParent`. The catvod-specific orchestration (Init/DexNative/InitOrigin boot dance, including polling) now lives in `providers-ts/providers/catvod/spider/jar.ts`; the catvod-free `JarLoader.kt` exposes only generic Android-dex plumbing.

**Goal:** Extend Insomnia's JS provider with primitive host APIs that are fully agnostic to any specific protocol. All protocol knowledge lives in TypeScript.

---

## Design Principle

Kotlin provides the minimum primitives needed:

1. **Load** — download, verify, and make a JAR's classes available via `DexClassLoader`
2. **Reflect** — call any public method on any class from a loaded JAR by name
3. **Eval** — fetch a remote JS URL and eval it in the current QuickJS context, with caching
4. **Sync HTTP** — a blocking `_http()` host call, required because drpy2 makes synchronous HTTP requests internally and QuickJS has no native `XMLHttpRequest`

Kotlin has zero knowledge of CatVod, Spider, `csp_*`, or any JAR-specific convention. The TypeScript CatVod provider owns all of that.

---

## Host API

### `host.jar.load`

```typescript
host.jar.load(args: {
  url: string;
  md5?: string;
}): Promise<void>
```

Downloads the JAR to local cache (skips if already cached and MD5 matches), creates a `DexClassLoader`, and stores it keyed by `md5(url)`. Idempotent.

### `host.jar.reflect`

```typescript
host.jar.reflect(args: {
  url:       string;           // JAR URL — identifies which DexClassLoader to use
  cls:       string;           // fully-qualified class name
  method:    string;           // method name
  instance?: string;           // opaque handle from a prior reflect call that returned an object
  args?:     unknown[];        // method arguments (primitives, strings, arrays)
}): Promise<string>            // JSON-serialized return value, or opaque handle for objects
```

Kotlin resolves the class from the `DexClassLoader` for `url`, resolves the method by name and argument count, invokes it, and returns the result as a JSON string. For non-primitive return values (objects), returns an opaque handle that can be passed back as `instance` on future calls.

### `host.eval.script`

```typescript
host.eval.script(args: {
  url: string;          // remote JS URL to fetch and eval
  cache?: boolean;      // default true — skip re-fetch if already evaled this session
}): Promise<void>
```

Fetches the JS at `url`, evals it in the current QuickJS context via `evalSnippet`, and caches the result for the session. Subsequent calls with the same URL are no-ops. Allows a provider to inject any JS library without bundling anything in the provider itself.

### `_http` — Sync HTTP (injected as a global)

drpy2 calls HTTP synchronously inside rule evaluation. QuickJS has no built-in blocking network primitive, so Kotlin must provide one as a `@JSMethod`-style global named `_http`:

```typescript
// Injected by Kotlin onto globalThis — not called directly by providers
_http(url: string, options: {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  timeout?: number;
  encoding?: string;    // e.g. "gbk"
  buffer?: number;      // 2 = return base64 of binary body
  withHeaders?: boolean;
}): { content: string; headers: Record<string, string>; statusCode: number }
```

This is a **blocking** call that suspends the QuickJS engine thread until the response arrives. The async `req()` wrapper that drpy2 uses is built on top of `_http` in `http.js` (FongMi's pattern), which the provider fetches via `host.eval.script`.

`local.get/set/delete` and `setTimeout` are also injected by Kotlin as globals (not `host.*` namespaced) since drpy2 expects them on `globalThis` directly.

---

## Kotlin Implementation

### `JarLoader.kt` — new file in `providers/js/`

```kotlin
class JarLoader(private val context: Context) {

    private val loaders   = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, Any>()
    private val instanceKeyGen = AtomicLong(0)

    fun load(url: String, md5: String?) {
        val key = md5(url)
        if (loaders.containsKey(key)) return
        val jar    = downloadAndVerify(url, md5)
        val dexOut = File(context.codeCacheDir, "dex/$key").also { it.mkdirs() }
        val soOut  = File(context.cacheDir, "so/$key").also { it.mkdirs() }
        extractNativeLibs(jar, soOut)           // no-op if jar has no assets/*.so
        loaders[key] = DexClassLoader(
            jar.absolutePath, dexOut.absolutePath,
            soOut.absolutePath, context.classLoader
        )
    }

    fun reflect(url: String, cls: String, method: String,
                instanceHandle: String?, rawArgs: JsonArray): String {
        val loader   = loaders[md5(url)] ?: error("JAR not loaded: $url")
        val clz      = loader.loadClass(cls)
        val instance = instanceHandle?.let { instances[it] }

        // Resolve method by name + arg count (same as FongMi's approach)
        val args     = rawArgs.toJvmArgs()
        val m        = clz.methods.first { it.name == method && it.parameterCount == args.size }
        val result   = m.invoke(instance, *args)

        return when (result) {
            null       -> "null"
            is String  -> JsonPrimitive(result).toString()
            is Boolean -> JsonPrimitive(result).toString()
            is Number  -> JsonPrimitive(result).toString()
            else       -> {
                // Non-primitive: store and return an opaque handle
                val handle = "obj_${instanceKeyGen.incrementAndGet()}"
                instances[handle] = result
                JsonPrimitive(handle).toString()
            }
        }
    }

    fun clear() {
        instances.clear()
        loaders.clear()
    }
}
```

`extractNativeLibs` extracts any `assets/*.so` entries to `soOut` before creating the `DexClassLoader` — needed for JARs that bundle native libs (e.g. FTY's encrypted JAR). It is a no-op for plain JARs.

### `EvalLoader.kt` — new file in `providers/js/`

Fetches remote JS and evals it into the engine. Caches by URL for the session.

```kotlin
class EvalLoader(private val httpClient: OkHttpClient) {
    private val evaled = ConcurrentHashMap<String, Boolean>()

    suspend fun evalScript(url: String, cache: Boolean, engine: QuickJsEngine) {
        if (cache && evaled.containsKey(url)) return
        val js = httpClient.newCall(Request.Builder().url(url).build())
            .execute().body!!.string()
        engine.evalSnippet(js)
        if (cache) evaled[url] = true
    }

    fun clear() = evaled.clear()
}
```

### Sync HTTP + global injections

Added to `QuickJsEngine` setup, injected onto `globalThis` before any provider code runs:

```kotlin
// Injected as blocking global — called synchronously from JS
ctx.getGlobalObject().setProperty("_http") { args ->
    val url     = args[0] as String
    val options = args[1] as? JSObject
    // Execute blocking OkHttp call on the engine thread
    // Return JSObject { content, headers, statusCode }
    executeSyncHttp(ctx, url, options)
}

ctx.getGlobalObject().setProperty("local", LocalKvStore(engineKey))

ctx.getGlobalObject().setProperty("setTimeout") { args ->
    val fn    = args[0] as JSFunction
    val delay = (args[1] as? Number)?.toLong() ?: 0
    engineScope.launch { delay(delay); fn.call() }
    null
}
```

`LocalKvStore` is a per-engine in-memory `ConcurrentHashMap` (or SQLite-backed for persistence across sessions).

### `HostApis.kt` — add `eval` namespace

```kotlin
"eval" -> when (name) {
    "script" -> {
        val args  = json.parseToJsonElement(argsJson).jsonObject
        val url   = args["url"]!!.jsonPrimitive.content
        val cache = args["cache"]?.jsonPrimitive?.boolean ?: true
        evalLoader.evalScript(url, cache, engine)
        "true"
    }
    else -> throw IllegalArgumentException("Unknown eval method: $name")
}
```

### `HOST_BOOTSTRAP_JS` — add `eval` namespace

```javascript
globalThis.host = {
  http:     ns('http'),
  crypto:   ns('crypto'),
  platform: ns('platform'),
  jar:      ns('jar'),
  eval:     ns('eval'),     // NEW
};
```



```kotlin
"jar" -> when (name) {
    "load"    -> {
        val args = json.parseToJsonElement(argsJson).jsonObject
        jarLoader.load(
            args["url"]!!.jsonPrimitive.content,
            args["md5"]?.jsonPrimitive?.contentOrNull
        )
        "true"
    }
    "reflect" -> {
        val args = json.parseToJsonElement(argsJson).jsonObject
        jarLoader.reflect(
            url            = args["url"]!!.jsonPrimitive.content,
            cls            = args["cls"]!!.jsonPrimitive.content,
            method         = args["method"]!!.jsonPrimitive.content,
            instanceHandle = args["instance"]?.jsonPrimitive?.contentOrNull,
            rawArgs        = args["args"]?.jsonArray ?: JsonArray(emptyList())
        )
    }
    "clear"   -> { jarLoader.clear(); "true" }
    else      -> throw IllegalArgumentException("Unknown jar method: $name")
}
```

### `QuickJsEngine.kt` — one new line in `dispatchHost`

```kotlin
"jar" -> hostApis.handleJar(name, argsJson)
```

### `HOST_BOOTSTRAP_JS` — add `jar` namespace

```javascript
globalThis.host = {
  http:     ns('http'),
  crypto:   ns('crypto'),
  platform: ns('platform'),
  jar:      ns('jar'),          // NEW
};
```

---

## TypeScript Usage (CatVod provider)

All CatVod conventions live here. Kotlin knows nothing of `Spider`, `Init`, `csp_*`.

```typescript
const JAR = config.spiderUrl;

// 1. Load the JAR once
await host.jar.load({ url: JAR, md5: config.spiderMd5 });

// 2. Bootstrap — call Init.init(context) if present (decrypts encrypted JARs)
await host.jar.reflect({
  url: JAR, cls: 'com.github.catvod.spider.Init', method: 'init', args: []
}).catch(() => {/* not all JARs have Init */});

// 3. Instantiate a Spider and call init(ext)
const spiderHandle = await host.jar.reflect({
  url: JAR, cls: `com.github.catvod.spider.${site.api.replace('csp_', '')}`,
  method: 'init', args: [site.ext ?? '']
});

// 4. Call Spider methods
const raw = await host.jar.reflect({
  url: JAR, cls: `com.github.catvod.spider.${site.api.replace('csp_', '')}`,
  instance: spiderHandle,
  method: 'homeContent', args: [false]
});
const data = JSON.parse(raw);
```

Instance caching (one Spider per site, reuse across calls) is managed in TypeScript, using the opaque handles returned by `reflect`.

---

## What Kotlin Knows vs What TypeScript Knows

| Concern | Owner |
|---------|-------|
| Download + cache JAR | Kotlin (`JarLoader`) |
| Extract native `.so` from JAR | Kotlin (`JarLoader`) |
| `DexClassLoader` lifecycle | Kotlin (`JarLoader`) |
| Reflect into any class/method | Kotlin (`JarLoader`) |
| CatVod `Spider` interface | TypeScript (CatVod provider) |
| `csp_*` class naming convention | TypeScript (CatVod provider) |
| `Init` / `Proxy` bootstrap | TypeScript (CatVod provider) |
| Spider instance lifecycle | TypeScript (CatVod provider) |
| Any other JAR convention | TypeScript (CatVod provider) |

---

## Caching

| Cache | Key | Lifecycle |
|-------|-----|-----------|
| JAR file on disk | `cacheDir/spiders/{filename}` | Persists; invalidated when MD5 changes |
| `DexClassLoader` | `md5(jarUrl)` | Engine session; cleared on `jar.clear()` |
| Object instance handles | auto-incremented key | Engine session; cleared on `jar.clear()` |
| Extracted `.so` | `cacheDir/so/{md5}/` | Persists alongside JAR |

---

## Files to Create / Modify

| File | Change |
|------|--------|
| `providers/js/src/.../JarLoader.kt` | New — JAR download, native extraction, DexClassLoader, reflect |
| `providers/js/src/.../EvalLoader.kt` | New — remote JS fetch + eval with session cache |
| `providers/js/src/.../HostApis.kt` | Add `handleJar()`, `handleEval()` |
| `providers/js/src/.../QuickJsEngine.kt` | Add `"jar"`, `"eval"` to `dispatchHost()`; inject `_http`, `local`, `setTimeout` globals; add to `HOST_BOOTSTRAP_JS` |
| `providers-ts/utils/types.ts` | Add `jar` and `eval` to `HostAPI` |

---

## Estimated Effort

| Task | Estimate |
|------|----------|
| `JarLoader.kt` (download, native extraction, DexClassLoader, reflect) | 1 day |
| `EvalLoader.kt` (fetch + eval + cache) | 0.5 day |
| Sync `_http` + `local` + `setTimeout` globals in `QuickJsEngine.kt` | 1 day |
| `HostApis.kt` + `HOST_BOOTSTRAP_JS` wiring | 0.5 day |
| `types.ts` | 0.5 day |
| Integration test | 1 day |
| **Total** | **~4.5 days** |
