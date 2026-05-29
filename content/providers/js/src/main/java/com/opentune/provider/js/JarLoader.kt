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
 * Protocol-agnostic: all domain conventions (class names, method names) are supplied by callers.
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
            loadJarFile(key, downloadAndVerify(url, md5), null)
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
            loadJarFile(key, dest, null)

            // Inject shim into the app classloader's parent chain so that any
            // DexClassLoader created by native code (e.g. DexNative.getLoader()) also
            // resolves shim classes via normal delegation — without this, native-created
            // loaders can't find com.github.catvod.crawler.Spider and the app crashes.
            try {
                val shimLoader = loaders[key]!!
                val appLoader  = ctx.classLoader
                val parentField = ClassLoader::class.java.getDeclaredField("parent")
                    .also { it.isAccessible = true }
                val originalParent = parentField.get(appLoader)
                parentField.set(shimLoader, originalParent)
                parentField.set(appLoader, shimLoader)
            } catch (_: Throwable) {}
        }
    }

    private fun loadJarFile(key: String, jar: File, parent: ClassLoader?) {
        val ctx    = ContextHolder.get()
        val gen    = loadGen.get()
        val dexOut = File(ctx.codeCacheDir, "dex/$key/$gen").also { it.mkdirs() }
        val soOut  = File(ctx.cacheDir, "so/$key").also { it.mkdirs() }
        extractNativeLibs(jar, soOut)
        loaders[key] = DexClassLoader(
            jar.absolutePath,
            dexOut.absolutePath,
            soOut.absolutePath,
            // Always use the full app classloader as parent so that app-bundled libraries
            // (okhttp3, gson, etc.) are visible to spider JARs and any loaders they create.
            parent ?: ctx.classLoader,
        )
    }

    /**
     * Runs a protocol-specific boot sequence after [load].
     *
     * [initClass]       — singleton that holds the Application reference and creates the secondary loader
     * [dexNativeClass]  — class whose <clinit> loads the native .so; force-loaded before init()
     * [initOriginClass] — class on the secondary loader that needs its own init(Context) call
     *
     * Boot steps (mirrors the FongMi Guard pattern but is entirely class-name-driven):
     *  1. Pre-set Application field on the initClass singleton (so DexNative.<clinit> sees non-null context).
     *  2. Force-load dexNativeClass to trigger <clinit>.
     *  3. Call initClass.init(Context) — decrypts guard, creates secondary DexClassLoader.
     *  4. Patch secondary loader's parent → primary (so the secondary can resolve classes from primary).
     *  5. Call initOriginClass.init(Context) on secondary loader — populates its context singleton.
     */
    fun boot(url: String, initClass: String, dexNativeClass: String, initOriginClass: String) {
        val primary = loaders[urlKey(url)] ?: error("JAR not loaded: $url")
        val ctx     = ContextHolder.get()

        val initCls = try { primary.loadClass(initClass) } catch (_: Throwable) { return }

        // Step 1: pre-set Application field before dexNativeClass.<clinit> runs
        try {
            val inst = initCls.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }?.invoke(null)
            if (inst != null) {
                inst.javaClass.declaredFields
                    .firstOrNull { it.type == android.app.Application::class.java }
                    ?.also { it.isAccessible = true }
                    ?.set(inst, ctx as? android.app.Application ?: ctx.applicationContext as? android.app.Application)
            }
        } catch (_: Throwable) {}

        // Step 2: force dexNativeClass <clinit> — UnsatisfiedLinkError means wrong arch, let it propagate
        try { primary.loadClass(dexNativeClass) } catch (e: UnsatisfiedLinkError) { throw e } catch (_: Throwable) {}

        // Step 3: initClass.init(Context) — this is where config.db DexClassLoader is created internally
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

        val parentField = ClassLoader::class.java.getDeclaredField("parent")
            .also { it.isAccessible = true }

        // Steps 4 & 5: get config.db loader, point its parent to primary (shim-jar) so gson is
        // visible via delegation, then call initOriginClass.init(Context) on it.
        val loaderMethod = initCls.methods.firstOrNull { it.name == "loader" && it.parameterCount == 0 }
            ?: return
        loaderMethod.isAccessible = true
        val secondaryLoader = loaderMethod.invoke(null) as? ClassLoader ?: return

        try {
            parentField.set(secondaryLoader, primary)
        } catch (_: Throwable) {}

        try {
            val originCls = secondaryLoader.loadClass(initOriginClass)
            originCls.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 1 &&
                m.parameterTypes[0].name == "android.content.Context"
            }?.also { it.isAccessible = true }?.invoke(null, ctx)
        } catch (_: Throwable) {}
    }

    /**
     * [factoryCls] / [factoryMethod] — optional fallback for newInstance: if [cls] can't be
     * loaded directly, calls factoryCls.factoryMethod(shortName) to obtain the instance.
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
        val loader   = loaders[urlKey(url)] ?: error("JAR not loaded: $url")
        val instance = instanceHandle?.let { instances[it] }

        if (method == "newInstance" && instance == null) {
            val clz = tryLoadClass(loader, cls)
            if (clz != null) {
                val obj    = clz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val handle = "obj_${keyGen.incrementAndGet()}"
                instances[handle] = obj
                return JsonPrimitive(handle).toString()
            }
            val shortName = cls.substringAfterLast('.')
            val obj = if (factoryCls != null && factoryMethod != null)
                invokeFactory(loader, factoryCls, factoryMethod, shortName) else null
            obj ?: error("Cannot instantiate $cls — not in loader and no factory provided")
            val handle = "obj_${keyGen.incrementAndGet()}"
            instances[handle] = obj
            return JsonPrimitive(handle).toString()
        }

        val clz      = tryLoadClass(loader, cls) ?: instance?.javaClass ?: error("Cannot load class $cls")
        val jvmArgs = buildArgs(clz, method, rawArgs)
        val m = resolveMethod(clz, method, jvmArgs.size)
            ?: error("No method '$method' with ${jvmArgs.size} params in $cls")
        m.isAccessible = true
        val result = try {
            m.invoke(instance, *jvmArgs)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            var root: Throwable = cause
            while (root.cause != null && root.cause !== root) root = root.cause!!
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

    private fun tryLoadClass(loader: ClassLoader, cls: String): Class<*>? =
        try { loader.loadClass(cls) } catch (_: Throwable) { null }

    private fun invokeFactory(loader: ClassLoader, factoryCls: String, factoryMethod: String, shortName: String): Any? =
        try {
            val factory = loader.loadClass(factoryCls)
            val m = factory.methods.firstOrNull { it.name == factoryMethod && it.parameterCount == 1 }
                ?: return null
            m.isAccessible = true
            m.invoke(null, shortName)
        } catch (_: Throwable) { null }

    fun clear() {
        instances.clear()
        loaders.clear()
        loadLocks.clear()
        loadGen.incrementAndGet()
    }

    /** Clears only spider instances, keeping loaded JARs. Re-init runs on next reflect call. */
    fun clearInstances() {
        instances.clear()
    }

    // ── Download ────────────────────────────────────────────────────────────

    private fun downloadAndVerify(url: String, md5: String?): File {
        val dir  = File(ContextHolder.get().cacheDir, "jars").also { it.mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { urlKey(url) }
        // Normalise to .jar extension — disguised JARs (e.g. .jpg) are common in CatVod configs
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
        // W^X policy: file must be read-only before DexClassLoader can load it
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

    /**
     * Finds a method by name and final arg count. If the only overload takes (Context, ...)
     * and TS passed one fewer arg, the Context is prepended automatically in [buildArgs].
     */
    private fun resolveMethod(clz: Class<*>, name: String, argCount: Int): java.lang.reflect.Method? =
        clz.methods.firstOrNull { it.name == name && it.parameterCount == argCount }
            ?: clz.methods.firstOrNull { m ->
                m.name == name &&
                m.parameterCount == argCount + 1 &&
                m.parameterTypes.firstOrNull()?.name == "android.content.Context"
            }

    /**
     * Converts JSON args to JVM types, injecting Android Context as first arg when the
     * target method's first parameter is Context but TS passed one fewer argument.
     */
    private fun buildArgs(clz: Class<*>, method: String, raw: JsonArray): Array<Any?> {
        // Prefer the Context-injecting overload (paramCount == raw.size + 1) so that
        // init(Context, String) wins over init(Context) when the caller passes one arg.
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
