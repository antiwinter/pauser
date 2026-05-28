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
 * All CatVod/Spider conventions are owned by the TypeScript layer. This class is protocol-agnostic.
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
            val ctx    = ContextHolder.get()
            val jar    = downloadAndVerify(url, md5)
            val gen    = loadGen.get()
            val dexOut = File(ctx.codeCacheDir, "dex/$key/$gen").also { it.mkdirs() }
            val soOut  = File(ctx.cacheDir, "so/$key").also { it.mkdirs() }
            extractNativeLibs(jar, soOut)
            val primary = DexClassLoader(
                jar.absolutePath,
                dexOut.absolutePath,
                soOut.absolutePath,
                ctx.classLoader,
            )

            // FongMi Guard JAR pattern: Init.init(Context) decrypts assets/ftyshinidie.guard into
            // a secondary DexClassLoader (config.db). Spider classes live in that secondary loader
            // and use InitOrigin (not spider.Init) as their context singleton.
            //
            // Boot sequence:
            //  1. Pre-set spider.Init.Application field so DexNative.<clinit> sees non-null context.
            //  2. Force-load DexNative to trigger <clinit> (loads ftyguard_v8.so).
            //  3. Call spider.Init.init(ctx) — decrypts guard, creates secondary DexClassLoader.
            //  4. Patch secondary loader's parent → primary DexClassLoader (so KL can find spider.Init).
            //  5. Call InitOrigin.init(ctx) on secondary loader — populates InitOrigin.N (Application).
            //     Without this, OB$tF.<clinit> → OB.<init> → getSharedPreferences(null) → NPE.
            val initCls = try {
                primary.loadClass("com.github.catvod.spider.Init")
            } catch (e: ClassNotFoundException) {
                // Init class not found — not a Guard JAR, that's fine
                null
            }

            initCls?.let { cls ->
                // Step 1: pre-set Application field before DexNative.<clinit> runs
                try {
                    val inst = cls.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
                        ?.also { it.isAccessible = true }?.invoke(null)
                    if (inst != null) {
                        inst.javaClass.declaredFields
                            .firstOrNull { it.type == android.app.Application::class.java }
                            ?.also { it.isAccessible = true }
                            ?.set(inst, ctx as? android.app.Application ?: ctx.applicationContext as? android.app.Application)
                    }
                } catch (_: Throwable) {}

                // Step 2: force DexNative <clinit> — throws UnsatisfiedLinkError on wrong arch
                primary.loadClass("com.github.catvod.spider.DexNative")

                // Step 3: Init.init(ctx) — bootstraps secondary loader
                val initMethod = cls.methods.firstOrNull { m ->
                    m.name == "init" && (m.parameterCount == 0 ||
                        (m.parameterCount == 1 && m.parameterTypes[0].name == "android.content.Context"))
                }
                if (initMethod != null) {
                    initMethod.isAccessible = true
                    val args = if (initMethod.parameterCount == 1) arrayOf(ctx) else emptyArray()
                    initMethod.invoke(null, *args)
                }

                // Steps 4 & 5: patch secondary loader and call InitOrigin.init
                val loaderMethod = cls.methods.firstOrNull { it.name == "loader" && it.parameterCount == 0 }
                if (loaderMethod != null) {
                    loaderMethod.isAccessible = true
                    val secondaryLoader = loaderMethod.invoke(null) as? ClassLoader
                    if (secondaryLoader != null) {
                        // Step 4: reparent secondary → primary so KL can resolve spider.Init
                        try {
                            ClassLoader::class.java.getDeclaredField("parent")
                                .also { it.isAccessible = true }
                                .set(secondaryLoader, primary)
                        } catch (_: Throwable) {}

                        // Step 5: InitOrigin.init(ctx) — populates context singleton in config.db
                        try {
                            val initOriginCls = secondaryLoader.loadClass("com.github.catvod.spider.InitOrigin")
                            initOriginCls.methods.firstOrNull { m ->
                                m.name == "init" && m.parameterCount == 1 &&
                                m.parameterTypes[0].name == "android.content.Context"
                            }?.also { it.isAccessible = true }?.invoke(null, ctx)
                        } catch (_: Throwable) {}
                    }
                }
            }
            loaders[key] = primary
        }
    }

    fun reflect(
        url: String,
        cls: String,
        method: String,
        instanceHandle: String?,
        rawArgs: JsonArray,
    ): String {
        val loader   = loaders[urlKey(url)] ?: error("JAR not loaded: $url")
        val instance = instanceHandle?.let { instances[it] }

        // For newInstance with no handle, try direct constructor first.
        // Encrypted JARs (Guard pattern) can't be instantiated directly — fall back to
        // Init.getSpider(siteKey) where siteKey is the last segment of the class name.
        if (method == "newInstance" && instance == null) {
            val clz = tryLoadClass(loader, url, cls)
            if (clz != null) {
                val obj    = clz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val handle = "obj_${keyGen.incrementAndGet()}"
                instances[handle] = obj
                return JsonPrimitive(handle).toString()
            }
            // Encrypted JAR: use Init.getSpider(shortClassName) to get the spider
            val shortName = cls.substringAfterLast('.')
            val obj = initGetSpider(loader, shortName)
                ?: error("Cannot instantiate $cls — not found in loader and Init.getSpider failed")
            val handle = "obj_${keyGen.incrementAndGet()}"
            instances[handle] = obj
            return JsonPrimitive(handle).toString()
        }

        val clz      = tryLoadClass(loader, url, cls) ?: instance?.javaClass ?: error("Cannot load class $cls")
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

    private fun tryLoadClass(loader: DexClassLoader, url: String, cls: String): Class<*>? =
        try { loader.loadClass(cls) } catch (_: Throwable) { null }

    private fun initGetSpider(loader: DexClassLoader, shortName: String): Any? =
        try {
            val initCls = loader.loadClass("com.github.catvod.spider.Init")
            val m = initCls.methods.firstOrNull { it.name == "getSpider" && it.parameterCount == 1 }
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
