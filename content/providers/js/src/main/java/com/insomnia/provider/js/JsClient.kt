package com.insomnia.provider.js

import com.insomnia.content.contract.EntryList
import com.insomnia.content.contract.EntryTag
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SortField
import com.insomnia.content.contract.SortOrder
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointValidationResult
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlatformInfoData
import com.insomnia.core.form.contract.QrResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Live endpoint client backed by a dedicated QuickJS context.
 *
 * One context per server — contexts are not shared between clients
 * so JS state (client credentials) is fully isolated.
 */
class JsClient(
    override var protocol: String,
    private val jsBundle: String,
    private val hostApis: HostApis,
    private val values: Map<String, String>,
    private val deviceInfo: PlatformInfoData,
) : EndpointClient() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = true }

    private lateinit var engine: QuickJsEngine
    private var initialized = false
    private val initMutex = Mutex()
    private val providerCtxs = ConcurrentHashMap<String, String>()

    /**
     * Dispatches `host.notification.send` payloads by [method]. Currently handles
     * `emit-entries` — decodes the result into an [com.insomnia.content.contract.EntryEmission]
     * and forwards it to the wrapper-supplied [entryEmitter]. No-op when no emitter
     * is attached (non-progressive caller).
     */
    private val notificationDispatcher: suspend (String, JsonObject?) -> Unit = { method, result ->
        when (method) {
            "emit-entries" -> EntryInfoCodec.parseEmission(result)?.let { e ->
                entryEmitter?.emit(e.items, e.totalCount, e.isComplete)
            }
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    override suspend fun test(): EndpointValidationResult {
        return try {
            ensureReady()
            val credsJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), values)
            val resultJson = engine.callMethod("test", """{"credentials":$credsJson}""")
                ?: return EndpointValidationResult.Error("Validation returned null")

            val obj = json.parseToJsonElement(resultJson).jsonObject
            val success = obj["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (success) {
                val fieldsEl = obj["fields"] ?: return EndpointValidationResult.Error("Missing fields in validation response")
                val fieldsObj = fieldsEl.jsonObject
                val fields = fieldsObj.mapValues { (_, v) -> v.jsonPrimitive.content }
                EndpointValidationResult.Success(fields = fields)
            } else {
                EndpointValidationResult.Error(obj["error"]?.jsonPrimitive?.content ?: "Validation failed")
            }
        } catch (e: Exception) {
            EndpointValidationResult.Error(e.message ?: "JS validation error")
        }
    }

    // ── QR flow ────────────────────────────────────────────────────────────

    override suspend fun getQr(): QrResult.QrReady? {
        return try {
            val resultJson = withEngine { engine ->
                engine.callMethod("getQr", "{}")
            } ?: return null
            val obj = json.parseToJsonElement(resultJson).jsonObject
            val token  = obj["token"]?.jsonPrimitive?.content ?: return null
            val qrData = obj["qrData"]?.jsonPrimitive?.content ?: return null
            QrResult.QrReady(token = token, qrData = qrData)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun pollQr(token: String): QrResult {
        return try {
            val tokenEl = runCatching { json.parseToJsonElement(token) }.getOrNull()
            if (tokenEl is JsonObject) {
                return QrResult.Confirmed(tokenEl.mapValues { (_, v) -> v.jsonPrimitive.content })
            }
            val args = buildJsonObject { put("token", token) }.toString()
            val resultJson = withEngine { engine ->
                engine.callMethod("pollQr", args)
            } ?: return QrResult.Error("null response")
            val obj = json.parseToJsonElement(resultJson).jsonObject
            when (obj["status"]?.jsonPrimitive?.content) {
                "confirmed" -> {
                    val fields = obj["fields"]?.jsonObject
                        ?.mapValues { (_, v) -> v.jsonPrimitive.content }
                        ?: emptyMap()
                    QrResult.Confirmed(fields)
                }
                "scanning"  -> QrResult.Scanning
                "scanned"   -> QrResult.Scanned
                "expired"   -> QrResult.Expired
                else        -> QrResult.Error(obj["error"]?.jsonPrimitive?.content ?: "unknown status")
            }
        } catch (e: Exception) {
            QrResult.Error(e.message ?: "pollQr error")
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private suspend fun <T> withEngine(block: suspend (QuickJsEngine) -> T): T {
        val engine = QuickJsEngine(hostApis, proxyClient.getHttpClient(), notificationDispatcher)
        return try {
            engine.init()
            engine.evalSnippet(HostBootstrap.JS)
            engine.evalBundle(jsBundle)
            block(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun ensureReady() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            engine = QuickJsEngine(hostApis, proxyClient.getHttpClient(), notificationDispatcher)
            engine.init()
            engine.evalSnippet(HostBootstrap.JS)
            engine.evalBundle(jsBundle)

            val deviceInfoJson = json.encodeToString(
                com.insomnia.player.PlatformInfoData.serializer(), deviceInfo,
            )
            val proxyConfigJson = proxyClient.getConfig()
                .let { Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it) }
            val credsJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), values)
            val initArgs = """{"credentials":$credsJson,"deviceInfo":$deviceInfoJson,"proxyConfig":$proxyConfigJson}"""
            engine.callMethod("init", initArgs)
            engine.evalSnippet("globalThis.__proxyConfig = $proxyConfigJson;")
            initialized = true
        }
    }

    // ── EndpointClient ───────────────────────────────────────────

    override suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        options: QueryOptions,
    ): EntryList {
        ensureReady()
        val args = buildJsonObject {
            put("location", if (location.isNullOrEmpty()) JsonNull else JsonPrimitive(location))
            put("startIndex", startIndex)
            put("limit", limit)
            put("options", json.encodeToJsonElement(options))
        }
        val resultJson = engine.callMethod("listEntry", args.toString())
            ?: return EntryList(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson).jsonObject
        return EntryInfoCodec.parseEntryList(obj) ?: EntryList(emptyList(), 0)
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        ensureReady()
        val args = buildJsonObject {
            put("itemRefs", JsonArray(itemRefs.map { JsonPrimitive(it) }))
        }
        val resultJson = engine.callMethod("getEntries", args.toString())
            ?: return EntryList(emptyList(), 0)
        val el = json.parseToJsonElement(resultJson)
        return if (el is JsonObject) {
            EntryInfoCodec.parseEntryList(el) ?: EntryList(emptyList(), 0)
        } else {
            val items = EntryInfoCodec.parseEntryArray(el.jsonArray)
            EntryList(items = items, totalCount = items.size)
        }
    }

    override suspend fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        ensureReady()
        val args = buildJsonObject {
            put("tag", tag.name)
            if (scopeLocation != null) put("scopeLocation", scopeLocation) else put("scopeLocation", JsonNull)
            put("startIndex", startIndex)
            put("limit", limit)
            sortBy?.let { put("sortBy", it.name) }
            put("sortOrder", sortOrder.name)
        }
        val resultJson = engine.callMethod("getTaggedEntries", args.toString())
            ?: return EntryList(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson).jsonObject
        return EntryInfoCodec.parseEntryList(obj) ?: EntryList(emptyList(), 0)
    }

    override suspend fun updateEntryState(itemRef: String, key: String, value: String?) {
        ensureReady()
        val ctxJson = providerCtxs[itemRef] ?: "null"
        val args = buildJsonObject {
            put("itemRef", itemRef)
            put("key", key)
            if (value != null) put("value", value) else put("value", JsonNull)
            put("ctx", json.parseToJsonElement(ctxJson))
        }
        try {
            engine.callMethod("updateEntryState", args.toString())
        } catch (e: Throwable) {
            Timber.w(e, "updateEntryState failed itemRef=$itemRef key=$key: ${e.message}")
        }
    }

    override val progressIntervalMs: Long = 0L

    override suspend fun getPlaybackSources(itemRef: String, startMs: Long): List<PlaybackSource> {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
            put("startMs", startMs)
        }
        val resultJson = engine.callMethod("getPlaybackSources", args.toString())
            ?: engine.callMethod("getPlaybackSpec", args.toString())
            ?: error("getPlaybackSources returned null")
        val resultEl = json.parseToJsonElement(resultJson)
        val obj = if (resultEl is kotlinx.serialization.json.JsonArray) {
            buildJsonObject { put("sources", resultEl) }
        } else {
            resultEl.jsonObject
        }
        return parsePlaybackSources(itemRef, obj)
    }

    private fun parsePlaybackSources(itemRef: String, obj: JsonObject): List<PlaybackSource> {
        val sourcesEl = obj["sources"]?.takeIf { it !is JsonNull }?.jsonArray
        val sources = if (sourcesEl != null) {
            sourcesEl.map { EntryInfoCodec.parseSource(it.jsonObject) }
        } else {
            // Backward compat: legacy flat format with url/headers at top level
            val urlSpecObj = obj["urlSpec"]?.takeIf { it !is JsonNull }?.jsonObject
            val url = obj["url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: urlSpecObj?.get("url")?.jsonPrimitive?.content
                ?: error("JS provider returned null URL")
            val headers = obj["headers"]?.takeIf { it !is JsonNull }?.jsonObject
                ?.mapValues { e -> e.value.jsonPrimitive.content }
                ?: urlSpecObj?.get("headers")?.takeIf { it !is JsonNull }?.jsonObject
                    ?.mapValues { e -> e.value.jsonPrimitive.content }
                ?: emptyMap()
            val mimeType = obj["mimeType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?: urlSpecObj?.get("mimeType")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            listOf(PlaybackSource(url = url, headers = headers, mimeType = mimeType))
        }

        val ctxEl = obj["ctx"] ?: obj["hooksCtx"]
        if (ctxEl != null && ctxEl !is JsonNull) providerCtxs[itemRef] = ctxEl.toString()

        return sources
    }
}
