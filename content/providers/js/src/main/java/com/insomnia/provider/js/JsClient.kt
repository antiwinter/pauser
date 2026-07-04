package com.insomnia.provider.js

import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryList
import com.insomnia.content.contract.EntryTag
import com.insomnia.content.contract.EntryUserData
import com.insomnia.player.MediaCodecInfo
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SearchQuery
import com.insomnia.content.contract.SortField
import com.insomnia.content.contract.SortOrder
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointValidationResult
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlatformInfoData
import com.insomnia.core.form.contract.QrResult
import com.insomnia.player.SubtitleTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    // ── Validation ─────────────────────────────────────────────────────────

    override suspend fun test(): EndpointValidationResult {
        return try {
            ensureReady()
            val resultJson = engine.callMethod("test", "{}")
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
        val engine = QuickJsEngine(hostApis, proxyClient.getHttpClient())
        return try {
            engine.init()
            engine.evalSnippet(JsProvider.HOST_BOOTSTRAP_JS)
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
            engine = QuickJsEngine(hostApis, proxyClient.getHttpClient())
            engine.init()
            engine.evalSnippet(JsProvider.HOST_BOOTSTRAP_JS)
            engine.evalBundle(jsBundle)

            val deviceInfoJson = json.encodeToString(
                com.insomnia.player.PlatformInfoData.serializer(), deviceInfo,
            )
            val proxyConfigJson = proxyClient.getConfig()
                .let { Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it) }
            val initArgs = """{"credentials":${Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), values)},"deviceInfo":$deviceInfoJson,"proxyConfig":$proxyConfigJson}"""
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
        val normalizedLocation = if (location.isNullOrEmpty()) null else location
        val args = buildJsonObject {
            if (normalizedLocation != null) put("location", normalizedLocation) else put("location", JsonNull)
            put("startIndex", startIndex)
            put("limit", limit)
            put("options", buildJsonObject {
                options.sortBy?.let { put("sortBy", it.name) }
                put("sortOrder", options.sortOrder.name)
                put("recursive", options.recursive)
                options.filterByType?.let { put("filterByType", it) }
            })
        }
        val argsStr = args.toString()
        val resultJson = engine.callMethod("listEntry", argsStr)
            ?: return EntryList(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson).jsonObject
        val items = obj["items"]?.jsonArray?.mapNotNull { parseListItem(it.jsonObject) } ?: emptyList()
        val totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return EntryList(items = items, totalCount = totalCount)
    }

    override suspend fun search(scopeLocation: String, query: SearchQuery): EntryList {
        ensureReady()
        val args = buildJsonObject {
            put("scopeLocation", scopeLocation)
            put("query", query.term)
            put("term", query.term)
            query.years?.let { put("years", kotlinx.serialization.json.JsonArray(it.map { y -> JsonPrimitive(y) })) }
            query.genres?.let { put("genres", kotlinx.serialization.json.JsonArray(it.map { g -> JsonPrimitive(g) })) }
            query.countries?.let { put("countries", kotlinx.serialization.json.JsonArray(it.map { c -> JsonPrimitive(c) })) }
            query.studios?.let { put("studios", kotlinx.serialization.json.JsonArray(it.map { s -> JsonPrimitive(s) })) }
            put("startIndex", query.startIndex)
            put("limit", query.limit)
            query.sortBy?.let { put("sortBy", it.name) }
            put("sortOrder", query.sortOrder.name)
        }
        val resultJson = engine.callMethod("search", args.toString())
            ?: return EntryList(emptyList(), 0)
        val all = json.parseToJsonElement(resultJson).jsonArray
            .mapNotNull { parseListItem(it.jsonObject) }
            .filter { it.type !in query.excludeTypes }
        return EntryList(items = all, totalCount = all.size)
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        ensureReady()
        val args = buildJsonObject {
            put("itemRefs", kotlinx.serialization.json.JsonArray(itemRefs.map { JsonPrimitive(it) }))
        }
        val resultJson = engine.callMethod("getEntries", args.toString())
            ?: return EntryList(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson)
        return if (obj is kotlinx.serialization.json.JsonObject) {
            EntryList(
                items = obj["items"]?.jsonArray?.mapNotNull { parseListItem(it.jsonObject) } ?: emptyList(),
                totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        } else {
            val items = obj.jsonArray.mapNotNull { parseListItem(it.jsonObject) }
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
        return EntryList(
            items = obj["items"]?.jsonArray?.mapNotNull { parseListItem(it.jsonObject) } ?: emptyList(),
            totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
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

    override suspend fun getPlaybackSources(itemRef: String): List<PlaybackSource> {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
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

    // ── Parsers ────────────────────────────────────────────────────────────

    private fun parseListItem(obj: JsonObject): EntryInfo? {
        val ref = obj["ref"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: ref
        val typeRaw = obj["type"]?.jsonPrimitive?.content ?: obj["kind"]?.jsonPrimitive?.content
        val cover = obj["cover"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: obj["coverUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val ud = obj["userData"]?.takeIf { it !is JsonNull }?.jsonObject
        return EntryInfo(
            ref = ref,
            title = title,
            type = typeRaw ?: "Unknown",
            cover = cover,
            userData = ud?.let {
                EntryUserData(
                    positionMs = it["positionMs"]?.takeIf { field -> field !is JsonNull }
                        ?.jsonPrimitive?.content?.toLongOrNull(),
                    isFavorite = it["isFavorite"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    played = it["played"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                )
            },
            originalTitle = obj["originalTitle"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            genres = obj["genres"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            communityRating = obj["communityRating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toFloatOrNull(),
            studios = obj["studios"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            etag = obj["etag"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            indexNumber = obj["indexNumber"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            overview = obj["overview"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            childCount = obj["childCount"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            parentRef = obj["parentRef"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            seriesRef = obj["seriesRef"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            seasonNumber = obj["seasonNumber"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            logo = obj["logo"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            backdrop = obj["backdrop"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            bitrate = obj["bitrate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            year = obj["year"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            durationMs = obj["durationMs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull(),
            width = obj["width"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            height = obj["height"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            officialRating = obj["officialRating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            filename = obj["filename"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            sources = obj["sources"]?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { el -> parseSource(el.jsonObject).takeIf { it.url.isNotEmpty() } },
        )
    }

    private fun parsePlaybackSources(itemRef: String, obj: JsonObject): List<PlaybackSource> {
        val sourcesEl = obj["sources"]?.takeIf { it !is JsonNull }?.jsonArray
        val sources = if (sourcesEl != null) {
            sourcesEl.map { parseSource(it.jsonObject) }
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

    private fun parseSource(obj: JsonObject): PlaybackSource {
        val url = obj["url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: ""
        val headers = obj["headers"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.mapValues { e -> e.value.jsonPrimitive.content } ?: emptyMap()
        val mimeType = obj["mimeType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val subtitleTracks = obj["subtitleTracks"]?.jsonArray?.mapNotNull { s ->
            val so = s.jsonObject
            val trackId = so["trackId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SubtitleTrack(
                trackId = trackId,
                label = so["label"]?.jsonPrimitive?.content ?: trackId,
                language = so["language"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                isDefault = so["isDefault"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                isForced = so["isForced"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                externalRef = so["externalRef"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
        } ?: emptyList()
        val mediaCodecs = obj["mediaCodecs"]?.takeIf { it !is JsonNull }?.jsonArray?.mapNotNull { s ->
            val so = s.jsonObject
            val codec = so["codec"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: return@mapNotNull null
            MediaCodecInfo(
                codec = codec,
                bitDepth = so["bitDepth"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
                bitrate = so["bitrate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            )
        } ?: emptyList()
        return PlaybackSource(url = url, headers = headers, mimeType = mimeType, subtitleTracks = subtitleTracks, mediaCodecs = mediaCodecs)
    }
}
