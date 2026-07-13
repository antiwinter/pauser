package com.insomnia.provider.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
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

    suspend fun handleHttp(name: String, argsJson: String, client: OkHttpClient): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "get"  -> executeHttp(args, "GET",  null, client)
            "post" -> executeHttp(args, "POST", args["body"]?.jsonPrimitive?.content, client)
            else   -> throw IllegalArgumentException("Unknown http method: $name")
        }
    }

    fun handleHttpSync(name: String, argsJson: String, client: OkHttpClient): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "get"  -> executeHttp(args, "GET",  null, client)
            "post" -> executeHttp(args, "POST", args["body"]?.jsonPrimitive?.content, client)
            else   -> throw IllegalArgumentException("Unknown http sync method: $name")
        }
    }

    private fun executeHttp(
        args: JsonObject,
        method: String,
        bodyStr: String?,
        client: OkHttpClient,
    ): String {
        val rawUrl = args["url"]?.jsonPrimitive?.content ?: error("http.$method: missing url")
        val url    = encodeIdnUrl(rawUrl)
        val hdrs   = args["headers"]?.jsonObject ?: JsonObject(emptyMap())

        val requestBuilder = Request.Builder().url(url)
        hdrs.forEach { (k, v) -> requestBuilder.header(k, v.jsonPrimitive.content) }

        val body = if (bodyStr != null) {
            val ct = args["contentType"]?.jsonPrimitive?.content ?: "application/json"
            bodyStr.toRequestBody(ct.toMediaType())
        } else if (method == "POST") {
            "".toRequestBody("application/json".toMediaType())
        } else null

        requestBuilder.method(method, body)
        val response = client.newCall(requestBuilder.build()).execute()
        response.use { resp ->
            val respBody = resp.body?.string() ?: ""
            val respHeaders = buildJsonObject {
                resp.headers.forEach { (k, v) -> put(k, v) }
            }
            return buildJsonObject {
                put("status", resp.code)
                put("body", respBody)
                put("headers", respHeaders)
            }.toString()
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
    /** Rejects `..` segments and any canonical path that escapes the sandbox root. */
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

    fun handleJar(name: String, argsJson: String, jarLoader: JarLoader): String? {
        val parsed = json.parseToJsonElement(argsJson)
        val args = if (parsed is JsonNull) JsonObject(emptyMap()) else parsed.jsonObject
        return when (name) {
            "load" -> {
                val source = args["source"]?.jsonObject
                    ?: throw IllegalArgumentException("host.jar.load: missing 'source' object")
                // Handle returned to JS for use in loadClass/reflect/registerLoader.
                JsonPrimitive(jarLoader.load(parseLoadSource(source))).toString()
            }
            "loadAsset" -> {
                // Removed in the provider-folder refactor. Shim JARs now live next to
                // `index.js` in `assets/<provider>/` and are auto-injected by
                // [JsProviderLoader] at bundle-load time. JS should never call this.
                Timber.w("host.jar.loadAsset called but the API was removed; " +
                         "co-locate the JAR in the provider's folder instead")
                JsonPrimitive("error: loadAsset removed; co-locate JAR in provider folder").toString()
            }
            "reflect" -> {
                val handle        = args["handle"]!!.jsonPrimitive.content
                val cls           = args["cls"]!!.jsonPrimitive.content
                val method        = args["method"]!!.jsonPrimitive.content
                val instance      = args["instance"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val rawArgs       = args["args"]?.takeIf { it !is JsonNull }?.jsonArray ?: JsonArray(emptyList())
                val factoryCls    = args["factoryCls"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val factoryMethod = args["factoryMethod"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                jarLoader.reflect(handle, cls, method, instance, rawArgs, factoryCls, factoryMethod)
            }
            "loadClass" -> {
                val handle = args["handle"]!!.jsonPrimitive.content
                val cls    = args["cls"]!!.jsonPrimitive.content
                jarLoader.loadClass(handle, cls)
                "true"
            }
            "registerLoader" -> {
                val handle        = args["handle"]!!.jsonPrimitive.content
                val instanceHandle = args["instanceHandle"]!!.jsonPrimitive.content
                jarLoader.registerLoader(handle, instanceHandle)
                "true"
            }
            "adoptParent" -> {
                val childKey  = args["childKey"]!!.jsonPrimitive.content
                val parentKey = args["parentKey"]!!.jsonPrimitive.content
                jarLoader.adoptParent(childKey, parentKey)
                "true"
            }
            "clearInstances" -> {
                jarLoader.clearInstances()
                "true"
            }
            else -> throw IllegalArgumentException("Unknown jar method: $name")
        }
    }

    private fun parseLoadSource(source: JsonObject): LoadSource = when {
        source["url"] is JsonPrimitive -> LoadSource.Url(source["url"]!!.jsonPrimitive.content)
        source["path"] is JsonPrimitive -> LoadSource.Path(source["path"]!!.jsonPrimitive.content)
        source["buffer"] is JsonPrimitive -> LoadSource.Buffer(source["buffer"]!!.jsonPrimitive.content)
        else -> throw IllegalArgumentException("host.jar.load: source must declare one of {url, path, buffer}")
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
