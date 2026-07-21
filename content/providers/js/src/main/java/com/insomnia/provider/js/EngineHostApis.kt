package com.insomnia.provider.js

import com.insomnia.content.contract.SERVER_PORT
import com.insomnia.content.contract.StreamRelayRegistry
import com.insomnia.proxy.contract.HostRemapDns
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Engine-scoped `host.*` namespaces (`dns`, `relay`, `web`, `notification`); backed by per-endpoint state. */
class EngineHostApis(
    private val httpClient: OkHttpClient,
    private val jarLoader: JarLoader,
    private val notificationDispatcher: suspend (method: String, result: JsonObject?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val webSniffer = WebSniffer()

    @Serializable private data class RelayRegisterArgs(val cls: String, val method: String, val token: String)
    @Serializable private data class RelayRegisterResult(val token: String, val baseUrl: String)

    @Serializable private data class WebDetectArgs(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val regex: List<String>,
        val exclude: List<String> = emptyList(),
        val script: List<String> = emptyList(),
        val timeoutMs: Long = 15_000L,
    )

    /** `host.dns.remap({from, to})` */
    fun handleDns(name: String, argsJson: String): String {
        if (name != "remap") throw IllegalArgumentException("Unknown dns method: $name")
        val args = json.parseToJsonElement(argsJson).let { if (it is JsonObject) it else JsonObject(emptyMap()) }
        val from = args["from"]?.let { it.jsonPrimitive.content } ?: error("dns.remap: missing from")
        val to = args["to"]?.let { it.jsonPrimitive.content } ?: error("dns.remap: missing to")
        (httpClient.dns as? HostRemapDns)?.remap(from, to)
        return "true"
    }

    fun handleRelay(name: String, argsJson: String): String {
        if (name != "register") throw IllegalArgumentException("Unknown relay method: $name")
        val args = json.decodeFromString<RelayRegisterArgs>(argsJson)
        // JarStreamRelayRecipe.get is process-wide per (cls, method), so re-register
        // from a re-evaluated JS bundle returns the same instance.
        StreamRelayRegistry.register(args.token, JarStreamRelayRecipe.get(jarLoader, args.cls, args.method))
        return json.encodeToString(
            RelayRegisterResult(args.token, "http://127.0.0.1:${SERVER_PORT}/relay/${args.token}")
        )
    }

    /**
     * `host.notification.send({ method, message?, result? })` — a generic JSON-RPC-ish
     * channel for JS providers to push host-side notifications. Dispatched by [method];
     * the [JsClient] registers handlers (e.g. `emit-entries` → [com.insomnia.content.contract.EntryEmitter]).
     */
    suspend fun handleNotification(name: String, argsJson: String): String? {
        if (name != "send") throw IllegalArgumentException("Unknown notification method: $name")
        val args = json.parseToJsonElement(argsJson).let { if (it is JsonObject) it else JsonObject(emptyMap()) }
        val method = args["method"]?.jsonPrimitive?.content ?: error("notification.send: missing method")
        val result = args["result"]?.let { if (it is JsonObject) it else null }
        notificationDispatcher(method, result)
        return null
    }

    /** `host.web.detect({url, headers?, regex, exclude?, script?, timeoutMs?})` */
    suspend fun handleWeb(name: String, argsJson: String): String? {
        if (name != "detect") throw IllegalArgumentException("Unknown web method: $name")
        val args = json.decodeFromString<WebDetectArgs>(argsJson)
        val match = webSniffer.detect(args.url, args.headers, args.regex, args.exclude, args.script, args.timeoutMs)
            ?: return null
        return json.encodeToString(match)
    }
}

