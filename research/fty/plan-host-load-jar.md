# Plan: `host.loadJar()` — Dynamic Spider JAR Loading

**Goal:** Allow a JS provider running in QuickJS to load a TVBox-compatible `spider.jar` at runtime and call its Spider methods via `host.spider.*`.

---

## Overview

```
JS provider (QuickJS)
    host.loadJar({ url, md5 })
        ↓ Kotlin: download JAR, verify MD5
        ↓ extract ftyguard_v*.so from JAR assets/
        ↓ System.loadLibrary() from temp dir
        ↓ DexClassLoader loads JAR
        ↓ Init class decrypts .guard payload
        ↓ Spider classes become available
    host.spider.homeContent({ filter })
    host.spider.categoryContent({ tid, pg, filter, extend })
    host.spider.detailContent({ ids })
    host.spider.playerContent({ flag, id, vipFlags })
    host.spider.searchContent({ key, quick, pg })
```

The JS provider never touches Java/DEX directly. It calls `host.loadJar()` once, then calls `host.spider.*` methods which dispatch to the loaded Spider instance.

---

## Architecture

### New host namespace: `spider`

Add to `QuickJsEngine.dispatchHost()`:

```kotlin
"spider" -> hostApis.handleSpider(name, argsJson)
```

Add to `HostApis.kt`:

```kotlin
fun handleSpider(name: String, argsJson: String): String?
```

### New host namespace: `jar`

```kotlin
"jar" -> hostApis.handleJar(name, argsJson, context)
```

---

## Implementation Steps

### Step 1 — `JarLoader.kt` (new file in `providers/js`)

Responsible for downloading, verifying, extracting, and loading a spider JAR.

```kotlin
class JarLoader(private val context: Context) {

    // Cache: jarUrl → loaded Spider class
    private val cache = ConcurrentHashMap<String, Class<*>>()

    suspend fun load(url: String, md5: String?): Class<*> {
        cache[url]?.let { return it }

        // 1. Download JAR to cache dir
        val jarFile = downloadJar(url, md5)

        // 2. Extract native .so from JAR assets/
        val soFile = extractNativeLib(jarFile)

        // 3. Load native lib
        System.load(soFile.absolutePath)

        // 4. DexClassLoader
        val dexOutputDir = File(context.codeCacheDir, "dex").also { it.mkdirs() }
        val loader = DexClassLoader(
            jarFile.absolutePath,
            dexOutputDir.absolutePath,
            null,
            context.classLoader
        )

        // 5. Load Init class and call it (triggers .guard decryption)
        val initClass = loader.loadClass("com.github.catvod.spider.Init")
        val initMethod = initClass.getMethod("getLoader", Context::class.java)
        initMethod.invoke(null, context)  // static call

        // 6. Cache and return Spider base class
        val spiderClass = loader.loadClass("com.github.catvod.crawler.Spider")
        cache[url] = spiderClass
        return spiderClass
    }

    private fun extractNativeLib(jarFile: File): File {
        val abi = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "v8" else "v7"
        val soName = "ftyguard_$abi.so"
        val outFile = File(context.cacheDir, soName)
        if (outFile.exists()) return outFile

        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("assets/$soName")
                ?: error("Native lib $soName not found in JAR")
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }

    private suspend fun downloadJar(url: String, md5: String?): File {
        val fileName = url.substringAfterLast("/").substringBefore(";")
        val outFile = File(context.cacheDir, "spiders/$fileName").also {
            it.parentFile?.mkdirs()
        }
        if (outFile.exists() && md5 != null && outFile.md5() == md5) return outFile

        // Download via OkHttp
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.body!!.byteStream().use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (md5 != null && outFile.md5() != md5) error("MD5 mismatch for $url")
        return outFile
    }
}
```

### Step 2 — `SpiderBridge.kt` (new file in `providers/js`)

Holds a loaded Spider instance and dispatches method calls.

```kotlin
class SpiderBridge(
    private val context: Context,
    private val spiderClass: Class<*>,
    private val spiderKey: String,
) {
    private var instance: Any? = null

    fun init(ext: String) {
        val cls = spiderClass.classLoader!!
            .loadClass("com.github.catvod.spider.$spiderKey")
        instance = cls.getDeclaredConstructor().newInstance()
        cls.getMethod("init", Context::class.java, String::class.java)
            .invoke(instance, context, ext)
    }

    fun homeContent(filter: Boolean): String =
        call("homeContent", filter) as String

    fun categoryContent(tid: String, pg: String, filter: Boolean,
                        extend: Map<String, String>): String =
        call("categoryContent", tid, pg, filter, HashMap(extend)) as String

    fun detailContent(ids: List<String>): String =
        call("detailContent", ids) as String

    fun playerContent(flag: String, id: String, vipFlags: List<String>): String =
        call("playerContent", flag, id, vipFlags) as String

    fun searchContent(key: String, quick: Boolean, pg: String): String =
        call("searchContent", key, quick, pg) as String

    private fun call(method: String, vararg args: Any?): Any? {
        val inst = instance ?: error("Spider not initialized")
        return inst.javaClass.methods
            .first { it.name == method && it.parameterCount == args.size }
            .invoke(inst, *args)
    }
}
```

### Step 3 — Extend `HostApis.kt`

Add two new namespaces:

```kotlin
// In HostApis.kt

private var jarLoader: JarLoader? = null
private var spiderBridge: SpiderBridge? = null

fun setContext(context: Context) {
    jarLoader = JarLoader(context)
}

suspend fun handleJar(name: String, argsJson: String): String? {
    val args = json.parseToJsonElement(argsJson).jsonObject
    return when (name) {
        "load" -> {
            val url = args["url"]!!.jsonPrimitive.content
            val md5 = args["md5"]?.jsonPrimitive?.contentOrNull
            val spiderKey = args["spiderKey"]!!.jsonPrimitive.content
            val ext = args["ext"]?.jsonPrimitive?.content ?: ""
            val spiderClass = jarLoader!!.load(url, md5)
            spiderBridge = SpiderBridge(context, spiderClass, spiderKey)
            spiderBridge!!.init(ext)
            JsonPrimitive(true).toString()
        }
        else -> throw IllegalArgumentException("Unknown jar method: $name")
    }
}

fun handleSpider(name: String, argsJson: String): String? {
    val bridge = spiderBridge ?: error("No spider loaded. Call host.jar.load() first.")
    val args = json.parseToJsonElement(argsJson).jsonObject
    return when (name) {
        "homeContent" -> {
            val filter = args["filter"]?.jsonPrimitive?.boolean ?: false
            bridge.homeContent(filter)
        }
        "categoryContent" -> {
            val tid = args["tid"]!!.jsonPrimitive.content
            val pg = args["pg"]?.jsonPrimitive?.content ?: "1"
            val filter = args["filter"]?.jsonPrimitive?.boolean ?: false
            val extend = args["extend"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            bridge.categoryContent(tid, pg, filter, extend)
        }
        "detailContent" -> {
            val ids = args["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
            bridge.detailContent(ids)
        }
        "playerContent" -> {
            val flag = args["flag"]!!.jsonPrimitive.content
            val id = args["id"]!!.jsonPrimitive.content
            val vipFlags = args["vipFlags"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            bridge.playerContent(flag, id, vipFlags)
        }
        "searchContent" -> {
            val key = args["key"]!!.jsonPrimitive.content
            val quick = args["quick"]?.jsonPrimitive?.boolean ?: false
            val pg = args["pg"]?.jsonPrimitive?.content ?: "1"
            bridge.searchContent(key, quick, pg)
        }
        else -> throw IllegalArgumentException("Unknown spider method: $name")
    }
}
```

### Step 4 — Extend `QuickJsEngine.dispatchHost()`

```kotlin
private suspend fun dispatchHost(ns: String, name: String, argsJson: String): String? =
    when (ns) {
        "http"     -> hostApis.handleHttp(name, argsJson, httpClient)
        "crypto"   -> hostApis.handleCrypto(name, argsJson)
        "platform" -> hostApis.handlePlatform(name, argsJson)
        "jar"      -> hostApis.handleJar(name, argsJson)      // NEW
        "spider"   -> hostApis.handleSpider(name, argsJson)   // NEW
        else       -> throw IllegalArgumentException("Unknown host namespace: $ns")
    }
```

### Step 5 — Expose in `types.ts`

```typescript
// In utils/types.ts

export interface HostAPI {
  http: { ... };
  crypto: { ... };
  platform: { ... };

  // NEW
  jar: {
    load(args: {
      url: string;
      md5?: string;
      spiderKey: string;
      ext?: string;
    }): Promise<void>;
  };

  spider: {
    homeContent(args: { filter?: boolean }): Promise<string>;
    categoryContent(args: {
      tid: string;
      pg?: string;
      filter?: boolean;
      extend?: Record<string, string>;
    }): Promise<string>;
    detailContent(args: { ids: string[] }): Promise<string>;
    playerContent(args: {
      flag: string;
      id: string;
      vipFlags?: string[];
    }): Promise<string>;
    searchContent(args: {
      key: string;
      quick?: boolean;
      pg?: string;
    }): Promise<string>;
  };
}
```

---

## Usage from a JS Provider

```typescript
// In a TVBox provider's init():
await host.jar.load({
  url: 'https://cdn.example.com/spider.jar',
  md5: 'abc123...',
  spiderKey: 'WoGGGuard',
  ext: '{"Cloud-drive":"tvfan/Cloud-drive.txt"}',
});

// Then in listEntry():
const raw = await host.spider.categoryContent({ tid, pg: String(startIndex / limit + 1) });
const data = JSON.parse(raw);
// data.list → VodItem[]
```

---

## Caching Strategy

- JAR files cached in `context.cacheDir/spiders/` by filename
- MD5 verified on each load; re-download if mismatch
- Native `.so` cached in `context.cacheDir/` by ABI
- `SpiderBridge` instance held per `QuickJsEngine` instance (one per endpoint)
- On engine close, call `spider.destroy()` if available

---

## Security Considerations

- Only load JARs from URLs explicitly configured by the user (not from arbitrary JS strings)
- Verify MD5 before loading
- The JAR runs in the same process — it has full app permissions
- Consider running in a separate process (`:spider` process) for isolation (future work)

---

## Files to Create/Modify

| File | Change |
|------|--------|
| `providers/js/.../JarLoader.kt` | New — JAR download, native lib extraction, DexClassLoader |
| `providers/js/.../SpiderBridge.kt` | New — Spider instance wrapper |
| `providers/js/.../HostApis.kt` | Add `handleJar()` and `handleSpider()` |
| `providers/js/.../QuickJsEngine.kt` | Add `jar` and `spider` to `dispatchHost()` |
| `providers-ts/utils/types.ts` | Add `jar` and `spider` to `HostAPI` |

---

## Estimated Effort

| Task | Estimate |
|------|----------|
| `JarLoader.kt` (download + native extraction + DexClassLoader) | 1 day |
| `SpiderBridge.kt` (reflection-based Spider dispatch) | 0.5 day |
| `HostApis.kt` extensions | 0.5 day |
| `types.ts` additions | 0.5 day |
| Integration test with FTY spider.jar | 1 day |
| **Total** | **~3.5 days** |
