package com.opentune.provider.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * Handles `host.*` API calls dispatched from JS via `__hostDispatch(ns, name, argsJson)`.
 *
 * Each method returns a JSON string (or null) that will be used to resolve the JS Promise.
 */
class HostApis(
    val deviceName: String,
    val deviceId: String,
    val clientVersion: String,
) {
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

    private fun executeHttp(
        args: JsonObject,
        method: String,
        bodyStr: String?,
        client: OkHttpClient,
    ): String {
        val url  = args["url"]?.jsonPrimitive?.content ?: error("http.$method: missing url")
        val hdrs = args["headers"]?.jsonObject ?: JsonObject(emptyMap())

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

    // ── config ─────────────────────────────────────────────────────────────

    fun handleConfig(name: String, argsJson: String): String? {
        val args = json.parseToJsonElement(argsJson).jsonObject
        return when (name) {
            "get" -> {
                val key = args["key"]?.jsonPrimitive?.content ?: ""
                val value = when (key) {
                    "deviceName"    -> deviceName
                    "deviceId"      -> deviceId
                    "clientVersion" -> clientVersion
                    else            -> throw IllegalArgumentException("Unknown config key: $key")
                }
                JsonPrimitive(value).toString()
            }
            else -> throw IllegalArgumentException("Unknown config method: $name")
        }
    }
}
