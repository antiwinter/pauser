package com.insomnia.provider.js

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import timber.log.Timber
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.IDN
import java.security.MessageDigest

/**
 * Handles `host.*` API calls dispatched from JS via `__hostDispatch(ns, name, argsJson)`.
 * Each method returns a JSON string (or null) that will be used to resolve the JS Promise.
 */
class HostApis(
    val providerName: String,
    val sandboxRoot: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class HttpRequestArgs(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val contentType: String? = null,
    )

    @Serializable private data class HttpResponseDto(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
    )

    @Serializable private data class JarSource(val url: String? = null, val path: String? = null, val buffer: String? = null)
    @Serializable private data class JarLoadArgs(val source: JarSource)

    @Serializable private data class JarReflectArgs(
        val handle: String,
        val cls: String,
        val method: String,
        val instance: String? = null,
        val args: List<JsonElement> = emptyList(),
        val factoryCls: String? = null,
        val factoryMethod: String? = null,
    )

    @Serializable private data class JarHandleArgs(val handle: String, val cls: String)
    @Serializable private data class JarRegisterLoaderArgs(val handle: String, val instanceHandle: String)
    @Serializable private data class JarAdoptParentArgs(val childKey: String, val parentKey: String)

    suspend fun handleHttp(
        name: String,
        argsJson: String,
        client: OkHttpClient,
        tracker: MutableSet<Call>? = null,
    ): String? {
        val method = when (name) {
            "get"  -> "GET"
            "post" -> "POST"
            else   -> throw IllegalArgumentException("Unknown http method: $name")
        }
        val args = json.decodeFromString<HttpRequestArgs>(argsJson)
        val bodyStr = if (method == "POST") args.body else null
        return executeHttp(args.url, args.headers, method, bodyStr, args.contentType, client, tracker)
    }

    fun handleHttpSync(
        name: String,
        argsJson: String,
        client: OkHttpClient,
        tracker: MutableSet<Call>? = null,
    ): String? {
        val method = when (name) {
            "get"  -> "GET"
            "post" -> "POST"
            else   -> throw IllegalArgumentException("Unknown http sync method: $name")
        }
        val args = json.decodeFromString<HttpRequestArgs>(argsJson)
        val bodyStr = if (method == "POST") args.body else null
        return executeHttp(args.url, args.headers, method, bodyStr, args.contentType, client, tracker)
    }

    private fun executeHttp(
        url: String,
        headers: Map<String, String>,
        method: String,
        bodyStr: String?,
        contentType: String?,
        client: OkHttpClient,
        tracker: MutableSet<Call>?,
    ): String {
        val requestBuilder = Request.Builder().url(encodeIdnUrl(url))
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val body = if (bodyStr != null) {
            val ct = contentType ?: "application/json"
            bodyStr.toRequestBody(ct.toMediaType())
        } else if (method == "POST") {
            "".toRequestBody("application/json".toMediaType())
        } else null

        requestBuilder.method(method, body)
        val call = client.newCall(requestBuilder.build())
        if (tracker != null) tracker.add(call)
        return try {
            call.execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                json.encodeToString(HttpResponseDto(resp.code, respBody, resp.headers.toMap()))
            }
        } finally {
            tracker?.remove(call)
        }
    }
    fun handleFs(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "write" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: error("fs.write: missing path")
                val content = args["content"]?.jsonPrimitive?.content ?: error("fs.write: missing content")
                val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf8"
                val file = resolve(path)
                file.parentFile?.mkdirs()
                when (encoding) {
                    "base64" -> file.writeBytes(java.util.Base64.getDecoder().decode(content))
                    else     -> file.writeText(content, Charsets.UTF_8)
                }
                JsonPrimitive(file.absolutePath).toString()
            }
            "read" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: error("fs.read: missing path")
                val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf8"
                val file = resolve(path)
                val payload = when (encoding) {
                    "base64" -> java.util.Base64.getEncoder().encodeToString(file.readBytes())
                    else     -> file.readText(Charsets.UTF_8)
                }
                JsonPrimitive(payload).toString()
            }
            "exists" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: error("fs.exists: missing path")
                JsonPrimitive(resolve(path).exists()).toString()
            }
            "delete" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: error("fs.delete: missing path")
                val file = resolve(path)
                val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
                JsonPrimitive(gone).toString()
            }
            else -> throw IllegalArgumentException("Unknown fs method: $name")
        }
    }
    /** Rejects `..` segments and any canonical path that escapes [sandboxRoot]. */
    private fun resolve(path: String): File {
        require(path.isNotBlank()) { "host.fs: empty path" }
        if (path.contains("..")) throw SecurityException("host.fs: '..' segment rejected: $path")
        val rel = path.trimStart('/')
        val target = File(sandboxRoot, rel)
        val canonical = target.canonicalPath
        val rootCanonical = sandboxRoot.canonicalPath
        val rootPrefix = if (rootCanonical.endsWith(File.separator)) rootCanonical else "$rootCanonical${File.separator}"
        if (canonical != rootCanonical && !canonical.startsWith(rootPrefix)) {
            throw SecurityException("host.fs: path escapes sandbox ($path → $canonical)")
        }
        return target
    }

    fun handleCrypto(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "checksum" -> {
                val algo = args["algo"]?.jsonPrimitive?.content ?: "sha-256"
                val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf8"
                val input = args["input"]?.jsonPrimitive?.content ?: ""
                val bytes = when (encoding) {
                    "base64" -> java.util.Base64.getDecoder().decode(input)
                    "hex"    -> hexDecode(input)
                    else     -> input.toByteArray(Charsets.UTF_8)
                }
                val digestName = when (algo.lowercase()) {
                    "md5"     -> "MD5"
                    "sha-1", "sha1" -> "SHA-1"
                    "sha-256", "sha256" -> "SHA-256"
                    "sha-512", "sha512" -> "SHA-512"
                    else -> throw IllegalArgumentException("crypto.checksum: unsupported algo '$algo'")
                }
                val hex = MessageDigest.getInstance(digestName)
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                JsonPrimitive(hex).toString()
            }
            else -> throw IllegalArgumentException("Unknown crypto method: $name")
        }
    }

    private fun hexDecode(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }

    fun handleLog(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        val msg = args["msg"]?.jsonPrimitive?.content ?: argsJson
        when (name) {
            "e" -> Timber.e(msg)
            "w" -> Timber.w(msg)
            else -> Timber.d(msg)
        }
        return null
    }

    suspend fun handleTimer(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "sleep" -> {
                val ms = args["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                delay(ms)
                null
            }
            else -> throw IllegalArgumentException("Unknown timer method: $name")
        }
    }

    fun handleJar(name: String, argsJson: String, jarLoader: JarLoader): String? = when (name) {
        "load" -> {
            val source = json.decodeFromString<JarLoadArgs>(argsJson).source
            // Handle returned to JS for use in loadClass/reflect/registerLoader.
            val loadSource = when {
                source.url != null    -> LoadSource.Url(source.url)
                source.path != null    -> LoadSource.Path(source.path)
                source.buffer != null  -> LoadSource.Buffer(source.buffer)
                else -> throw IllegalArgumentException("host.jar.load: source must declare one of {url, path, buffer}")
            }
            JsonPrimitive(jarLoader.load(loadSource)).toString()
        }
        "reflect" -> {
            val a = json.decodeFromString<JarReflectArgs>(argsJson)
            jarLoader.reflect(a.handle, a.cls, a.method, a.instance, JsonArray(a.args), a.factoryCls, a.factoryMethod)
        }
        "loadClass" -> {
            val a = json.decodeFromString<JarHandleArgs>(argsJson)
            jarLoader.loadClass(a.handle, a.cls)
            "true"
        }
        "registerLoader" -> {
            val a = json.decodeFromString<JarRegisterLoaderArgs>(argsJson)
            jarLoader.registerLoader(a.handle, a.instanceHandle)
            "true"
        }
        "adoptParent" -> {
            val a = json.decodeFromString<JarAdoptParentArgs>(argsJson)
            jarLoader.adoptParent(a.childKey, a.parentKey)
            "true"
        }
        "clearInstances" -> {
            jarLoader.clearInstances()
            "true"
        }
        else -> throw IllegalArgumentException("Unknown jar method: $name")
    }

}

private fun encodeIdnUrl(url: String): String = try {
    // URI constructor rejects non-ASCII hostnames — use URL + regex to extract and encode the host
    val match = Regex("^(https?://)([^/?#:]+)((?::\\d+)?(?:[/?#].*)?)$", RegexOption.IGNORE_CASE)
        .matchEntire(url) ?: return url
    val scheme = match.groupValues[1]
    val host   = match.groupValues[2]
    val rest   = match.groupValues[3]
    val encoded = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
    if (encoded == host) url else "$scheme$encoded$rest"
} catch (_: Exception) { url }
