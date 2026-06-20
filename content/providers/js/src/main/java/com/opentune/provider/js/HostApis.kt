package com.opentune.provider.js

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.IDN
import java.security.MessageDigest

/**
 * Handles `host.*` API calls dispatched from JS via `__hostDispatch(ns, name, argsJson)`.
 *
 * Each method returns a JSON string (or null) that will be used to resolve the JS Promise.
 */
class HostApis {
    private val json = Json { ignoreUnknownKeys = true }

    // ── http ───────────────────────────────────────────────────────────────

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

    // ── fs ─────────────────────────────────────────────────────────────────

    fun handleFs(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "write" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: error("fs.write: missing path")
                val content = args["content"]?.jsonPrimitive?.content ?: error("fs.write: missing content")
                val ctx = ContextHolder.get()
                val file = File(ctx.cacheDir, path)
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                JsonPrimitive(file.absolutePath).toString()
            }
            else -> throw IllegalArgumentException("Unknown fs method: $name")
        }
    }

    // ── crypto ─────────────────────────────────────────────────────────────

    fun handleCrypto(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "sha256" -> {
                val input = args["input"]?.jsonPrimitive?.content ?: ""
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toByteArray(Charsets.UTF_8))
                // Lowercase hex to match Kotlin: "%02x".format(b)
                val hex = digest.joinToString("") { b -> "%02x".format(b) }
                JsonPrimitive(hex).toString()
            }
            else -> throw IllegalArgumentException("Unknown crypto method: $name")
        }
    }

    // ── log ────────────────────────────────────────────────────────────────

    fun handleLog(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        val msg = args["msg"]?.jsonPrimitive?.content ?: argsJson
        when (name) {
            "e" -> Log.e("JsProvider", msg)
            else -> Log.d("JsProvider", msg)
        }
        return null
    }

    // ── jar ────────────────────────────────────────────────────────────────

    fun handleJar(name: String, argsJson: String, jarLoader: JarLoader): String? {
        val parsed = json.parseToJsonElement(argsJson)
        val args = if (parsed is JsonNull) JsonObject(emptyMap()) else parsed.jsonObject
        return when (name) {
            "load" -> {
                val url = args["url"]!!.jsonPrimitive.content
                val md5 = args["md5"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                jarLoader.load(url, md5)
                "true"
            }
            "loadAsset" -> {
                val name = args["name"]!!.jsonPrimitive.content
                jarLoader.loadAsset(name)
                "true"
            }
            "boot" -> {
                val url            = args["url"]!!.jsonPrimitive.content
                val initClass      = args["initClass"]!!.jsonPrimitive.content
                val dexNativeClass = args["dexNativeClass"]!!.jsonPrimitive.content
                val initOriginClass = args["initOriginClass"]!!.jsonPrimitive.content
                jarLoader.boot(url, initClass, dexNativeClass, initOriginClass)
                "true"
            }
            "reflect" -> {
                val url           = args["url"]!!.jsonPrimitive.content
                val cls           = args["cls"]!!.jsonPrimitive.content
                val method        = args["method"]!!.jsonPrimitive.content
                val instance      = args["instance"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val rawArgs       = args["args"]?.takeIf { it !is JsonNull }?.jsonArray ?: JsonArray(emptyList())
                val factoryCls    = args["factoryCls"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val factoryMethod = args["factoryMethod"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                jarLoader.reflect(url, cls, method, instance, rawArgs, factoryCls, factoryMethod)
            }
            "clear" -> {
                jarLoader.clear()
                "true"
            }
            "clearInstances" -> {
                jarLoader.clearInstances()
                "true"
            }
            else -> throw IllegalArgumentException("Unknown jar method: $name")
        }
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
