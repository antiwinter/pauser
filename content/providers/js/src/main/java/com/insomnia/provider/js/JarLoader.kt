package com.insomnia.provider.js

import com.insomnia.content.contract.ByteSink
import com.insomnia.content.contract.StreamRelayResult
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * Source bytes handed to [JarLoader.load]. The host resolves the path / decodes the
 * buffer / fetches the URL; [JarLoader] never escapes [JarLoader.sandboxRoot].
 */
sealed class LoadSource {
    /** Kotlin downloads to `sandboxRoot/jars/<urlKey(url)>.jar`, then loads. */
    data class Url(val url: String) : LoadSource()

    /** Pre-validated by [HostApis.resolve]. */
    data class Path(val path: String) : LoadSource()

    /** Kotlin decodes, writes to `sandboxRoot/jars/<sha256(bytes)>.jar`, loads. */
    data class Buffer(val bufferB64: String) : LoadSource()
}

/**
 * All state (downloaded JARs, extracted .so files) lives under [sandboxRoot].
 * Dex output stays under [Context.codeCacheDir] so the OS can reclaim it.
 */
class JarLoader(
    private val sandboxRoot: File,
    private val httpClient: OkHttpClient,
) {
    private val jarsDir = File(sandboxRoot, "jars").also { it.mkdirs() }

    private val loaders   = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, Any>()
    private val keyGen    = AtomicLong(0)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val loadGen   = AtomicLong(0)  // incremented on clear() so each reload gets a fresh dex dir

    /** clinit must run before reflect; some plugins load .so in static initializers. */
    fun loadClass(url: String, cls: String) {
        val primary = loaders[urlKey(url)] ?: error("JAR not loaded: $url")
        try {
            primary.loadClass(cls)
        } catch (e: UnsatisfiedLinkError) {
            throw e
        } catch (_: Throwable) {
            throw NoClassDefFoundError("loadClass($cls) failed for $url")
        }
    }

    fun registerLoader(key: String, instanceHandle: String) {
        val instance = instances[instanceHandle]
            ?: error("Instance not found: $instanceHandle")
        val cl = instance as? ClassLoader
            ?: error("Instance $instanceHandle is not a ClassLoader")
        @Suppress("UNCHECKED_CAST")
        loaders[key] = cl as DexClassLoader
    }

    /**
     * Plugin runtimes that build their own DexClassLoader at runtime need this so the
     * runtime can resolve shim classes through the app classloader. [childKey] is a
     * loader key or instance handle; [parentKey] `"context"` → app classloader, else
     * a registered loader key.
     */
    fun adoptParent(childKey: String, parentKey: String) {
        val child: ClassLoader = loaders[childKey] ?: instances[childKey] as? ClassLoader
            ?: error("Child not found or not a ClassLoader: $childKey")
        val parent: ClassLoader = when (parentKey) {
            "context" -> ContextHolder.get().classLoader
            else      -> loaders[parentKey] ?: instances[parentKey] as? ClassLoader
                ?: error("Parent not found or not a ClassLoader: $parentKey")
        }
        val parentField = ClassLoader::class.java.getDeclaredField("parent")
            .also { it.isAccessible = true }
        parentField.set(child, parent)
    }
    /** `Path` variant is pre-validated by [HostApis.resolve]. */
    fun load(source: LoadSource) {
        val key = sourceKey(source)
        if (loaders.containsKey(key)) return
        synchronized(loadLocks.getOrPut(key) { Any() }) {
            if (loaders.containsKey(key)) return
            val file = when (source) {
                is LoadSource.Url    -> download(source.url)
                is LoadSource.Path   -> File(source.path)
                is LoadSource.Buffer -> writeBuffer(source.bufferB64)
            }
            loadJarFile(key, file)
        }
    }

    fun loadAsset(assetName: String) {
        val key = "asset:$assetName"
        if (loaders.containsKey(key)) return
        synchronized(loadLocks.getOrPut(key) { Any() }) {
            if (loaders.containsKey(key)) return
            val ctx  = ContextHolder.get()
            val dest = File(jarsDir, assetName)
            ctx.assets.open(assetName).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            // After injection, bootstrap classes resolve via ctx.classLoader.
            ClassPathInjector.inject(ctx, dest)
        }
    }

    private fun loadJarFile(key: String, jar: File) {
        val ctx    = ContextHolder.get()
        val gen    = loadGen.get()
        val dexOut = File(ctx.codeCacheDir, "dex/$key/$gen").also { it.mkdirs() }
        val soOut  = File(sandboxRoot, "so/$key").also { it.mkdirs() }
        extractNativeLibs(jar, soOut)
        loaders[key] = DexClassLoader(
            jar.absolutePath,
            dexOut.absolutePath,
            soOut.absolutePath,
            ctx.classLoader,
        )
    }
    /** `method == "newInstance"` (with no instance handle) → direct constructor call. */
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

    /** Unpacks the plugin's `Object[]{status, mime, InputStream, headers}` and pumps via IO. */
    fun invokeStreaming(
        cls: String,
        method: String,
        params: Map<String, String>,
    ): StreamRelayResult? {
        val rawArgs = JsonArray(listOf(
            buildJsonObject { params.forEach { (k, v) -> put(k, v) } }
        ))
        for ((_, loader) in loaders) {
            val clz = tryLoadClass(loader, cls) ?: continue
            val jvmArgs = try { buildArgs(clz, method, rawArgs) } catch (_: Throwable) { continue }
            val m = resolveMethod(clz, method, jvmArgs.size) ?: continue
            m.isAccessible = true
            val result = try {
                m.invoke(null, *jvmArgs)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                null // spider threw (e.g. upstream unreachable) — try next loader
            } catch (_: Throwable) { null } ?: continue
            @Suppress("UNCHECKED_CAST")
            val arr = result as? Array<Any?> ?: continue
            val status = (arr.getOrNull(0) as? Int) ?: 200
            val mime = arr.getOrNull(1) as? String
            val stream = arr.getOrNull(2) as? java.io.InputStream ?: continue
            val headers = (arr.getOrNull(3) as? Map<String, String>) ?: emptyMap()
            val pump: suspend (ByteSink) -> Unit = { sink ->
                val buf = ByteArray(PUMP_CHUNK_SIZE)
                try {
                    while (true) {
                        val read = withContext(Dispatchers.IO) { stream.read(buf) }
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                    }
                } finally {
                    withContext(Dispatchers.IO) { runCatching { stream.close() } }
                }
            }
            return StreamRelayResult(status, mime, headers, null, pump)
        }
        return null
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

    fun clearInstances() {
        instances.clear()
    }
    private fun download(url: String): File {
        val dest = File(jarsDir, "${urlKey(url)}.jar")
        if (dest.exists()) return dest
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "JAR download failed: HTTP ${r.code} $url" }
            dest.writeBytes(r.body!!.bytes())
        }
        return dest
    }

    private fun writeBuffer(bufferB64: String): File {
        val bytes = Base64.getDecoder().decode(bufferB64)
        val dest = File(jarsDir, "${sha256Hex(bytes).take(16)}.jar")
        if (!dest.exists()) dest.writeBytes(bytes)
        return dest
    }

    private fun sourceKey(source: LoadSource): String = when (source) {
        is LoadSource.Url  -> urlKey(source.url)
        is LoadSource.Path -> "path:${sha256Hex(source.path.toByteArray()).take(16)}"
        is LoadSource.Buffer -> "buf:${sha256Hex(Base64.getDecoder().decode(source.bufferB64)).take(16)}"
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
    private fun urlKey(url: String): String = sha256Hex(url.toByteArray()).take(16)
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
