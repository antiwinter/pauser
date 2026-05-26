package com.opentune.provider.js

import dalvik.system.DexClassLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

    // ── Public API ─────────────────────────────────────────────────────────

    fun load(url: String, md5: String?) {
        val key = urlKey(url)
        if (loaders.containsKey(key)) return
        synchronized(loadLocks.getOrPut(key) { Any() }) {
            if (loaders.containsKey(key)) return
            val ctx    = ContextHolder.get()
            val jar    = downloadAndVerify(url, md5)
            val dexOut = File(ctx.codeCacheDir, "dex/$key").also { it.mkdirs() }
            val soOut  = File(ctx.cacheDir, "so/$key").also { it.mkdirs() }
            extractNativeLibs(jar, soOut)
            loaders[key] = DexClassLoader(
                jar.absolutePath,
                dexOut.absolutePath,
                soOut.absolutePath,
                ctx.classLoader,
            )
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
        val clz      = loader.loadClass(cls)
        val instance = instanceHandle?.let { instances[it] }

        // newInstance (no handle) → invoke default constructor
        if (method == "newInstance" && instance == null) {
            val obj    = clz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            val handle = "obj_${keyGen.incrementAndGet()}"
            instances[handle] = obj
            return JsonPrimitive(handle).toString()
        }

        val jvmArgs = buildArgs(clz, method, rawArgs)
        val m = resolveMethod(clz, method, jvmArgs.size)
            ?: error("No method '$method' with ${jvmArgs.size} params in $cls")
        m.isAccessible = true
        val result = m.invoke(instance, *jvmArgs)

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

    fun clear() {
        instances.clear()
        loaders.clear()
        loadLocks.clear()
    }

    // ── Download ────────────────────────────────────────────────────────────

    private fun downloadAndVerify(url: String, md5: String?): File {
        val dir  = File(ContextHolder.get().cacheDir, "jars").also { it.mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { urlKey(url) }
        val dest = File(dir, "$name.jar")

        if (dest.exists() && md5 != null && dest.md5hex() == md5) return dest

        httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "JAR download failed: HTTP ${r.code} $url" }
            dest.writeBytes(r.body!!.bytes())
        }
        if (md5 != null) {
            val actual = dest.md5hex()
            check(actual == md5) { "JAR MD5 mismatch ($url): expected=$md5 actual=$actual" }
        }
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
        val candidate = clz.methods.firstOrNull { m ->
            m.name == method && (
                m.parameterCount == raw.size ||
                (m.parameterCount == raw.size + 1 &&
                 m.parameterTypes.firstOrNull()?.name == "android.content.Context")
            )
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
