package com.insomnia.provider.js

import com.insomnia.content.contract.SERVER_PORT
import com.insomnia.content.contract.StreamRelayRegistry
import com.insomnia.proxy.contract.HostRemapDns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Handles the engine-scoped `host.*` namespaces (`dns`, `relay`, `web`) — those backed by
 * per-endpoint state (this engine's HTTP client, jar loader, and WebView sniffer) rather than
 * the shared, stateless [HostApis] singleton.
 *
 * Each method returns a JSON string (or null) used to resolve the JS Promise.
 */
class EngineHostApis(
    private val httpClient: OkHttpClient,
    private val jarLoader: JarLoader,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val webSniffer = WebSniffer()

    /** `host.dns.remap({from, to})` — register a DNS host remap on this endpoint's client. */
    fun handleDns(name: String, argsJson: String): String {
        if (name != "remap") throw IllegalArgumentException("Unknown dns method: $name")
        val args = json.parseToJsonElement(argsJson).jsonObject
        val from = args["from"]?.jsonPrimitive?.content ?: error("dns.remap: missing from")
        val to = args["to"]?.jsonPrimitive?.content ?: error("dns.remap: missing to")
        (httpClient.dns as? HostRemapDns)?.remap(from, to)
        return "true"
    }

    fun handleRelay(name: String, argsJson: String): String {
        val args = json.parseToJsonElement(argsJson).jsonObject
        when (name) {
            "register" -> {
                val cls = args["cls"]?.jsonPrimitive?.content ?: error("relay.register: missing cls")
                val method = args["method"]?.jsonPrimitive?.content ?: error("relay.register: missing method")
                val recipe = JarStreamRelayRecipe(jarLoader, cls, method)
                val token = StreamRelayRegistry.register(recipe)
                return buildJsonObject {
                    put("token", token)
                    put("baseUrl", "http://127.0.0.1:${SERVER_PORT}/relay/$token")
                }.toString()
            }
            else -> throw IllegalArgumentException("Unknown relay method: $name")
        }
    }

    /** `host.web.detect({url, headers?, regex, exclude?, script?, timeoutMs?})` — headless WebView sniff. */
    suspend fun handleWeb(name: String, argsJson: String): String? {
        if (name != "detect") throw IllegalArgumentException("Unknown web method: $name")
        val args = json.parseToJsonElement(argsJson).jsonObject
        val url = args["url"]?.jsonPrimitive?.content ?: error("web.detect: missing url")
        val headers = args["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val regex = args["regex"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: error("web.detect: missing regex")
        val exclude = args["exclude"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val script = args["script"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 15_000L

        val match = webSniffer.detect(url, headers, regex, exclude, script, timeoutMs)
            ?: return null
        return buildJsonObject {
            put("url", match.url)
            put("headers", buildJsonObject {
                for ((k, v) in match.headers) put(k, v)
            })
        }.toString()
    }
}
