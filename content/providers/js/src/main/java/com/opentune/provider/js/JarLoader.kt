package com.opentune.provider.js

import dalvik.system.DexClassLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * Downloads and caches Android JARs, then reflectively invokes methods on their classes.
 *
 * Bootstrap injection model:
 *  1. loadAsset() → bootstrap dex is injected into the app classloader.
 *     After injection, bootstrap classes are resolvable via ctx.classLoader.
 *  2. load() → DexClassLoader with ctx.classLoader as parent.
 *     The parent chain is: loaded.jar → app(bootstrap+kotlin+okhttp) → boot
 *  3. boot() → Init.init() creates secondary loader; we patch its parent → ctx.classLoader.
 *     Chain: secondary → app(bootstrap+kotlin+okhttp) → boot
 */
class JarLoader(private val httpClient: OkHttpClient) {

    private val loaders   = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, Any>()
    private val keyGen    = AtomicLong(0)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val loadGen   = AtomicLong(0)  // incremented on clear() so each reload gets a fresh dex dir

    // ── Public API ─────────────────────────────────────────────────────────

    fun load(url: String, md5: String?) {
        val key = urlKey(url)
        if (loaders.containsKey(key)) return
        synchronized(loadLocks.getOrPut(key) { Any() }) {
            if (loaders.containsKey(key)) return
            loadJarFile(key, downloadAndVerify(url, md5))
        }
    }

    fun loadAsset(assetName: String) {
        val key = "asset:$assetName"
        if (loaders.containsKey(key)) return
        synchronized(loadLocks.getOrPut(key) { Any() }) {
            if (loaders.containsKey(key)) return
            val ctx  = ContextHolder.get()
            val dir  = File(ctx.cacheDir, "jars").also { it.mkdirs() }
            val dest = File(dir, assetName)
            dest.setWritable(true)
            ctx.assets.open(assetName).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            dest.setReadOnly()

            // Inject bootstrap dex elements into the app classloader.
            // After injection, bootstrap classes are resolvable via ctx.classLoader.
            ClassPathInjector.inject(ctx, dest)
        }
    }

    private fun loadJarFile(key: String, jar: File) {
        val ctx    = ContextHolder.get()
        val gen    = loadGen.get()
        val dexOut = File(ctx.codeCacheDir, "dex/$key/$gen").also { it.mkdirs() }
        val soOut  = File(ctx.cacheDir, "so/$key").also { it.mkdirs() }
        extractNativeLibs(jar, soOut)
        // Parent = app classloader, which now contains bootstrap classes via injection.
        loaders[key] = DexClassLoader(
            jar.absolutePath,
            dexOut.absolutePath,
            soOut.absolutePath,
            ctx.classLoader,
        )
    }

    /**
     * Boot sequence:
     *  1. Force-load DexNative class → triggers <clinit> which extracts & loads native .so
     *  2. Call Init.init(Context) → sets Application ref, calls DexNative.getLoader()
     *     which creates secondary DexClassLoader + spawns background thread
     *  3. Poll Init.loader() until secondary DexClassLoader is ready
     *  4. Patch secondary loader's parent → ctx.classLoader (app with bootstrap injected)
     *  5. Call secondary init(Context)
     */
    fun boot(url: String, initClass: String, dexNativeClass: String, initOriginClass: String) {
        val primary = loaders[urlKey(url)] ?: error("JAR not loaded: $url")
        val ctx     = ContextHolder.get()
        val initCls = try { primary.loadClass(initClass) } catch (_: Throwable) { return }

        // Step 1: force-load DexNative class — triggers <clinit> which extracts
        // and loads the native .so. UnsatisfiedLinkError = wrong arch.
        try {
            primary.loadClass(dexNativeClass)
        } catch (e: UnsatisfiedLinkError) { throw e } catch (_: Throwable) {}

        // Step 2: Init.init(Context) — sets Application ref, creates secondary loader
        val initMethod = initCls.methods.firstOrNull { m ->
            m.name == "init" && (m.parameterCount == 0 ||
                (m.parameterCount == 1 && m.parameterTypes[0].name == "android.content.Context"))
        }
        if (initMethod != null) {
            initMethod.isAccessible = true
            val args = if (initMethod.parameterCount == 1) arrayOf(ctx) else emptyArray()
            try { initMethod.invoke(null, *args) } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                if (cause is UnsatisfiedLinkError) throw cause
            } catch (_: Throwable) {}
        }

        // Step 3: poll Init.loader() until secondary DexClassLoader is ready
        val loaderMethod = initCls.methods.firstOrNull { it.name == "loader" && it.parameterCount == 0 }
        if (loaderMethod == null) return
        loaderMethod.isAccessible = true
        var secondaryLoader: DexClassLoader? = null
        for (attempt in 0..20) {
            val raw = loaderMethod.invoke(null)
            if (raw is DexClassLoader) {
                secondaryLoader = raw
                break
            }
            Thread.sleep(50)
        }
        if (secondaryLoader == null) return

        // Step 4: patch secondary loader's parent → ctx.classLoader
        // The app classloader now contains bootstrap classes via ClassPathInjector injection.
        val parentField = ClassLoader::class.java.getDeclaredField("parent")
            .also { it.isAccessible = true }
        try { parentField.set(secondaryLoader, ctx.classLoader) } catch (_: Throwable) {}

        loaders["secondary:${urlKey(url)}"] = secondaryLoader

        // Step 5: InitOrigin.init(Context) on secondary loader
        try {
            val originCls = secondaryLoader.loadClass(initOriginClass)
            originCls.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 1 &&
                m.parameterTypes[0].name == "android.content.Context"
            }?.also { it.isAccessible = true }?.invoke(null, ctx)
        } catch (_: Throwable) {}
    }

    /**
     * Reflective method invocation.
     *
     * newInstance: direct constructor call.
     * The Guard subclass internally calls Init.getSpider(shortName) which
     * loads the actual implementation from secondary loader via the native .so.
     */
    fun reflect(
        url: String,
        cls: String,
        method: String,
        instanceHandle: String?,
        rawArgs: JsonArray,
        factoryCls: String? = null,
        factoryMethod: String? = null,
    ): String {
        val urlKeyVal = urlKey(url)
        val primaryLoader   = loaders[urlKeyVal] ?: error("JAR not loaded: $url")
        val secondaryLoader = loaders["secondary:$urlKeyVal"]
        val instance = instanceHandle?.let { h -> instances[h] }

        if (method == "newInstance" && instance == null) {
            val clz = tryLoadClass(primaryLoader, cls)
                ?: secondaryLoader?.let { tryLoadClass(it, cls) }
                ?: error("Cannot load class $cls in primary or secondary loader")
            val obj = clz.getDeclaredConstructor()
                .also { it.isAccessible = true }
                .newInstance()
            val handle = "obj_${keyGen.incrementAndGet()}"
            instances[handle] = obj
            return JsonPrimitive(handle).toString()
        }

        val clz = tryLoadClass(primaryLoader, cls)
            ?: secondaryLoader?.let { tryLoadClass(it, cls) }
            ?: instance?.javaClass
            ?: error("Cannot load class $cls")
        val jvmArgs = buildArgs(clz, method, rawArgs)
        val m = resolveMethod(clz, method, jvmArgs.size)
            ?: error("No method '$method' with ${jvmArgs.size} params in $cls")
        m.isAccessible = true
        val result = try {
            m.invoke(instance, *jvmArgs)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            throw cause
        }

        return when (result) {
            null       -> "null"
            is String  -> JsonPrimitive(result).toString()
            is Boolean -> JsonPrimitive(result).toString()
            is Number  -> JsonPrimitive(result).toString()
            else       -> {
                val handle = "obj_${keyGen.incrementAndGet()}"
                instances[handle] = result
                JsonPrimitive(handle).toString()
            }
        }
    }

    private fun buildArgs(clz: Class<*>, method: String, raw: JsonArray): Array<Any?> {
        val candidate = clz.methods.firstOrNull { m ->
            m.name == method &&
            m.parameterCount == raw.size + 1 &&
            m.parameterTypes.firstOrNull()?.name == "android.content.Context"
        } ?: clz.methods.firstOrNull { m ->
            m.name == method && m.parameterCount == raw.size
        }
        val paramTypes = candidate?.parameterTypes
            ?: return Array(raw.size) { jsonToAny(raw[it]) }

        val injectCtx = paramTypes.firstOrNull()?.name == "android.content.Context" &&
                        raw.size < paramTypes.size
        return Array(paramTypes.size) { i ->
            if (i == 0 && injectCtx) ContextHolder.get()
            else convertToParam(raw[if (injectCtx) i - 1 else i], paramTypes[i])
        }
    }

    private fun tryLoadClass(loader: ClassLoader, cls: String): Class<*>? =
        try { loader.loadClass(cls) } catch (_: Throwable) { null }

    fun clear() {
        instances.clear()
        loaders.clear()
        loadLocks.clear()
        loadGen.incrementAndGet()
    }

    /** Clears only jar instances, keeping loaded JARs. Re-init runs on next reflect call. */
    fun clearInstances() {
        instances.clear()
    }

    // ── Download ────────────────────────────────────────────────────────────

    private fun downloadAndVerify(url: String, md5: String?): File {
        val dir  = File(ContextHolder.get().cacheDir, "jars").also { it.mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { urlKey(url) }
        val baseName = name.substringBeforeLast('.').ifBlank { name }
        val dest = File(dir, "$baseName.jar")

        if (dest.exists() && md5 != null && dest.md5hex() == md5) return dest

        dest.setWritable(true)
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "JAR download failed: HTTP ${r.code} $url" }
            dest.writeBytes(r.body!!.bytes())
        }
        if (md5 != null) {
            val actual = dest.md5hex()
            check(actual == md5) { "JAR MD5 mismatch ($url): expected=$md5 actual=$actual" }
        }
        dest.setReadOnly()
        return dest
    }

    private fun extractNativeLibs(jar: File, soOut: File) {
        try {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".so") }
                    .forEach { entry ->
                        val out = File(soOut, entry.name.substringAfterLast('/'))
                        if (!out.exists()) {
                            zip.getInputStream(entry).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                        }
                    }
            }
        } catch (_: Exception) { /* JAR has no native libs — fine */ }
    }

    // ── Method resolution ───────────────────────────────────────────────────

    private fun resolveMethod(clz: Class<*>, name: String, argCount: Int): java.lang.reflect.Method? =
        clz.methods.firstOrNull { it.name == name && it.parameterCount == argCount }
            ?: clz.methods.firstOrNull { m ->
                m.name == name &&
                m.parameterCount == argCount + 1 &&
                m.parameterTypes.firstOrNull()?.name == "android.content.Context"
            }

    private fun jsonToAny(el: JsonElement): Any? = when (el) {
        is JsonNull      -> null
        is JsonPrimitive -> el.booleanOrNull ?: el.doubleOrNull ?: el.content
        is JsonArray     -> el.map { jsonToAny(it) }
        is JsonObject    -> el.toString()
    }

    private fun convertToParam(el: JsonElement, type: Class<*>): Any? {
        if (el is JsonNull) return null
        val p = el as? JsonPrimitive
        return when {
            type == String::class.java ->
                p?.content ?: el.toString()
            type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java ->
                p?.booleanOrNull ?: false
            type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                p?.content?.toIntOrNull() ?: 0
            type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java ->
                p?.content?.toLongOrNull() ?: 0L
            type.isAssignableFrom(java.util.ArrayList::class.java) ->
                (el as? JsonArray)
                    ?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
                    ?.let { java.util.ArrayList(it) }
                    ?: java.util.ArrayList<String>()
            type.isAssignableFrom(java.util.HashMap::class.java) ->
                (el as? JsonObject)
                    ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
                    ?.let { java.util.HashMap(it) }
                    ?: java.util.HashMap<String, String>()
            else -> p?.content ?: el.toString()
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun urlKey(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}

private fun File.md5hex(): String =
    MessageDigest.getInstance("MD5")
        .digest(readBytes())
        .joinToString("") { "%02x".format(it) }
