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
 * buffer / fetches the URL; [JarLoader] never writes into [JarLoader.sandboxRoot].
 */
sealed class LoadSource {
    /** Kotlin downloads and writes to `codeCacheDir/jars/<urlKey(url)>.jar`. */
    data class Url(val url: String) : LoadSource()

    /** Pre-validated by [HostApis.resolve]; relative to [JarLoader.sandboxRoot]. */
    data class Path(val path: String) : LoadSource()

    /** Kotlin decodes and writes to `codeCacheDir/jars/<sha256(bytes)>.jar`. */
    data class Buffer(val bufferB64: String) : LoadSource()
}

/**
 * Loaded JARs and extracted .so files live under [Context.codeCacheDir] so the OS
 * can reclaim them. The host never writes into [sandboxRoot] — it's provider-owned
 * free-form space (rule #5 in the provider-layout plan).
 */
class JarLoader(
    private val sandboxRoot: File,
    private val httpClient: OkHttpClient,
) {
    private val loaders   = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, Any>()
    private val keyGen    = AtomicLong(0)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val loadGen   = AtomicLong(0)  // incremented on clear() so each reload gets a fresh dex dir

    /** [handle] is the value [load] returned. clinit must run before reflect; some plugins load
     *  .so in static initializers. */
    fun loadClass(handle: String, cls: String) {
        val primary = loaders[handle] ?: error("JAR not loaded: handle=$handle")
        try {
            primary.loadClass(cls)
        } catch (e: UnsatisfiedLinkError) {
            throw e
        } catch (_: Throwable) {
            throw NoClassDefFoundError("loadClass($cls) failed for handle=$handle")
        }
    }

    fun registerLoader(handle: String, instanceHandle: String) {
        val instance = instances[instanceHandle]
            ?: error("Instance not found: $instanceHandle")
        val cl = instance as? ClassLoader
            ?: error("Instance $instanceHandle is not a ClassLoader")
        @Suppress("UNCHECKED_CAST")
        loaders["secondary:$handle"] = cl as DexClassLoader
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
    /** `Path` variant is pre-validated by [HostApis.resolve]. Returns a stable opaque handle
     *  the caller passes to [loadClass]/[reflect]/[registerLoader]. The handle lets Path/Url/Buffer
     *  sources share a single lookup space without the caller re-deriving keys from the URL. */
    fun load(source: LoadSource): String {
        val key = sourceKey(source)
        if (!loaders.containsKey(key)) {
            synchronized(loadLocks.getOrPut(key) { Any() }) {
                if (!loaders.containsKey(key)) {
                    // For Path sources the producer is empty — bytes come from the sandbox
                    // file. We pre-compute the staged path so stageJar can hardlink sandbox
                    // → code_cache directly (rule #4 in the provider-layout plan).
                    val staged = if (source is LoadSource.Path) {
                        val src = resolveAgainstSandbox(source.path)
                        stageFromSandbox(key, src)
                    } else {
                        stageJar(key) {
                            when (source) {
                                is LoadSource.Url    -> fetchUrl(source.url)
                                is LoadSource.Buffer -> Base64.getDecoder().decode(source.bufferB64)
                                is LoadSource.Path   -> error("unreachable: handled above")
                            }
                        }
                    }
                    loadJarFile(key, staged)
                }
            }
        }
        return key
    }

    /** Co-located JARs in a provider's `assets/<provider>/` folder are auto-injected by
     *  [com.insomnia.provider.js.JsProviderLoader] at bundle load time; the JS surface no
     *  longer carries [loadAsset]. Kept for the test surface and one-shot bootstrap. */
    fun loadAsset(assetName: String): String {
        val key = "asset:$assetName"
        if (!loaders.containsKey(key)) {
            synchronized(loadLocks.getOrPut(key) { Any() }) {
                if (!loaders.containsKey(key)) {
                    val ctx  = ContextHolder.get()
                    val dest = File(stageDir(), "${dexFileName(key)}.jar")
                    if (!dest.exists()) {
                        ctx.assets.open(assetName).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                        dest.setReadOnly()
                    }
                    ClassPathInjector.inject(ctx, dest)
                }
            }
        }
        return key
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
        handle: String,
        cls: String,
        method: String,
        instanceHandle: String?,
        rawArgs: JsonArray,
        factoryCls: String? = null,
        factoryMethod: String? = null,
    ): String {
        val primaryLoader   = loaders[handle] ?: error("JAR not loaded: handle=$handle")
        val secondaryLoader = loaders["secondary:$handle"]
        val instance = instanceHandle?.let { h -> instances[h] }

        if (method == "newInstance" && instance == null) {
            val clz = tryLoadClass(primaryLoader, cls)
                ?: secondaryLoader?.let { tryLoadClass(it, cls) }
                ?: error("Cannot load class $cls in primary or secondary loader")
            val obj = clz.getDeclaredConstructor()
                .also { it.isAccessible = true }
                .newInstance()
            val instanceKey = "obj_${keyGen.incrementAndGet()}"
            instances[instanceKey] = obj
            return JsonPrimitive(instanceKey).toString()
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
                val instanceKey = "obj_${keyGen.incrementAndGet()}"
                instances[instanceKey] = result
                JsonPrimitive(instanceKey).toString()
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
    /** Downloads [url] and returns its bytes. Used as a [BytesProducer] for [stageJar]. */
    private fun fetchUrl(url: String): ByteArray =
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "JAR download failed: HTTP ${r.code} $url" }
            r.body!!.bytes()
        }

    /**
     * Stage a JAR exactly once on disk at `codeCacheDir/jars/<safe>.jar`. The producer
     * yields the bytes (URL/Buffer). The host never publishes anything into the
     * sandbox; sandbox-path sources are handled by [stageFromSandbox].
     *
     * The staged file is mode `0400` (owner-read only) so Android 13+ ART accepts it
     * under DexFile's non-writable-ancestor check.
     */
    private fun stageJar(key: String, producer: () -> ByteArray): File {
        val staged = stagedPath(key)
        if (!staged.exists()) {
            staged.writeBytes(producer())
            staged.setReadOnly()
        }
        return staged
    }

    /**
     * Stage a JAR by linking (or, on cross-FS / link-not-permitted, copying) a file the
     * provider placed in its sandbox into `codeCacheDir/jars/<safe>.jar`. Hardlink
     * direction is **always sandbox → code_cache** (rule #4): the sandbox file is the
     * source, the staged file is the destination. We never publish anything back into
     * the sandbox.
     */
    private fun stageFromSandbox(key: String, srcInSandbox: File): File {
        val staged = stagedPath(key)
        if (!staged.exists()) {
            runCatching {
                java.nio.file.Files.createLink(staged.toPath(), srcInSandbox.toPath())
            }.getOrElse {
                // Cross-FS or link not permitted — copy bytes from sandbox into code_cache.
                staged.writeBytes(srcInSandbox.readBytes())
            }
            staged.setReadOnly()
        }
        return staged
    }

    private fun stagedPath(key: String): File = File(stageDir(), "${dexFileName(key)}.jar")

    /** `codeCacheDir/jars/`. Android 13+ DexFile refuses jars under group/world-writable
     *  parents — strip those bits on the staging dir and its parent on first use per inode. */
    private fun stageDir(): File {
        val ctx = ContextHolder.get()
        val dir = File(ctx.codeCacheDir, "jars").also { it.mkdirs() }
        DexFilePermissions.chmodForDex(dir)
        DexFilePermissions.chmodForDex(ctx.codeCacheDir)
        return dir
    }

    /** DexClassLoader splits its jar path on `:` (path-list separator). Handles like
     *  `path:abc123…` become two nonsense files. Translate the in-memory key to a colon-free
     *  filename for files on disk. */
    private fun dexFileName(key: String) =
        key.replace(':', '_').replace('/', '_')

    private fun sourceKey(source: LoadSource): String = when (source) {
        is LoadSource.Url  -> urlKey(source.url)
        is LoadSource.Path -> "path:${sha256Hex(source.path.toByteArray()).take(16)}"
        is LoadSource.Buffer -> "buf:${sha256Hex(Base64.getDecoder().decode(source.bufferB64)).take(16)}"
    }

    /** Path sources are sandbox-relative — the JS sees the path through host.fs which is
     *  rooted at sandboxRoot, so load({path: ...}) must read from the same root. Without this
     *  anchor `File("jars/foo.jar")` would resolve against process CWD (`/` on Android).
     *  Read-only access: we only read here; the resulting bytes are written to code_cache. */
    private fun resolveAgainstSandbox(relPath: String): File {
        require(!relPath.contains("..")) { "host.jar.load: '..' segment rejected: $relPath" }
        return File(sandboxRoot, relPath.trimStart('/'))
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
