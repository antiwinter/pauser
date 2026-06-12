package com.opentune.provider.js

import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.EntryTag
import com.opentune.content.contract.EntryUserData
import com.opentune.player.MediaCodecInfo
import com.opentune.player.OpenTunePlaybackHooks
import com.opentune.content.contract.QueryOptions
import com.opentune.content.contract.SearchQuery
import com.opentune.content.contract.SortField
import com.opentune.content.contract.SortOrder
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointValidationResult
import com.opentune.player.PlatformInfoData
import com.opentune.core.form.contract.QrResult
import com.opentune.player.PlaybackSpec
import com.opentune.player.SubtitleTrack
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
import okhttp3.OkHttpClient

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


    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private lateinit var engine: QuickJsEngine
    private var initialized = false
    private val initMutex = Mutex()

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
            val resultJson = withEngine(proxyClient?.getHttpClient() ?: OkHttpClient()) { engine ->
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

    // pollQr: if the token is a JSON object, treat it as pre-computed Confirmed fields
    // (used by providers like benchmark that encode results directly in the token).
    // Otherwise delegate to JS.
    override suspend fun pollQr(token: String): QrResult {
        return try {
            val tokenEl = runCatching { json.parseToJsonElement(token) }.getOrNull()
            if (tokenEl is JsonObject) {
                return QrResult.Confirmed(tokenEl.mapValues { (_, v) -> v.jsonPrimitive.content })
            }
            val args = buildJsonObject { put("token", token) }.toString()
            val resultJson = withEngine(proxyClient?.getHttpClient() ?: OkHttpClient()) { engine ->
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

    private suspend fun <T> withEngine(httpClient: OkHttpClient, block: suspend (QuickJsEngine) -> T): T {
        val engine = QuickJsEngine(hostApis, httpClient)
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
            val effectiveHttpClient = proxyClient?.getHttpClient() ?: OkHttpClient()
            engine = QuickJsEngine(hostApis, effectiveHttpClient)
            engine.init()
            engine.evalSnippet(JsProvider.HOST_BOOTSTRAP_JS)
            engine.evalBundle(jsBundle)

            val deviceInfoJson = Json.encodeToString(
                com.opentune.player.PlatformInfoData.serializer(), deviceInfo,
            )
            val proxyConfigJson = proxyClient?.getConfig()
                ?.let { Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it) }
                ?: "null"
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
        val args = buildJsonObject {
            if (location != null) put("location", location) else put("location", JsonNull)
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

    override suspend fun tagEntry(itemRef: String, tag: EntryTag, value: Boolean) {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
            put("tag", tag.name)
            put("value", value)
        }
        runCatching { engine.callMethod("tagEntry", args.toString()) }
    }

    override suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
            put("startMs", startMs)
        }
        val resultJson = engine.callMethod("getPlaybackSpec", args.toString())
            ?: error("getPlaybackSpec returned null")
        return parsePlaybackSpec(json.parseToJsonElement(resultJson).jsonObject)
    }

    // ── Parsers ────────────────────────────────────────────────────────────

    private fun parseListItem(obj: JsonObject): EntryInfo? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: id
        val typeRaw = obj["type"]?.jsonPrimitive?.content ?: obj["kind"]?.jsonPrimitive?.content
        val cover = obj["cover"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: obj["coverUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val ud = obj["userData"]?.takeIf { it !is JsonNull }?.jsonObject
        return EntryInfo(
            id = id,
            title = title,
            type = typeRaw ?: "Unknown",
            cover = cover,
            userData = ud?.let {
                EntryUserData(
                    positionMs = it["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
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
            parentId = obj["parentId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            seriesId = obj["seriesId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
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
        )
    }

    private fun parsePlaybackSpec(obj: JsonObject): PlaybackSpec {
        val urlSpecObj = obj["urlSpec"]?.takeIf { it !is JsonNull }?.jsonObject
        val url = obj["url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: urlSpecObj?.get("url")?.jsonPrimitive?.content
        val headers = obj["headers"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.mapValues { e -> e.value.jsonPrimitive.content }
            ?: urlSpecObj?.get("headers")?.takeIf { it !is JsonNull }?.jsonObject
                ?.mapValues { e -> e.value.jsonPrimitive.content }
            ?: emptyMap()
        val mimeType = obj["mimeType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: urlSpecObj?.get("mimeType")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val subtitles = obj["subtitleTracks"]?.jsonArray?.mapNotNull { s ->
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

        val hooks = JsPlaybackHooks(
            engine = engine,
            hooksStateJson = obj["hooksState"]?.toString() ?: "{}",
        )

        val mediaCodecs = obj["mediaCodecs"]?.takeIf { it !is JsonNull }?.jsonArray?.mapNotNull { s ->
            val so = s.jsonObject
            val codec = so["codec"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: return@mapNotNull null
            MediaCodecInfo(
                codec = codec,
                bitDepth = so["bitDepth"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            )
        } ?: emptyList()

        return PlaybackSpec(
            url = requireNotNull(url) { "JS provider returned null URL in getPlaybackSpec" },
            headers = headers,
            mimeType = mimeType,
            hooks = hooks,
            subtitleTracks = subtitles,
            httpClient = proxyClient?.getHttpClient() ?: OkHttpClient(),
            mediaCodecs = mediaCodecs,
        )
    }
}

/**
 * Delegates playback hook calls back into the JS client.
 */
private class JsPlaybackHooks(
    private val engine: QuickJsEngine,
    private val hooksStateJson: String,
) : OpenTunePlaybackHooks {

    override fun progressIntervalMs(): Long = 10_000L

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) {
        callHook("onPlaybackReady", buildJsonObject {
            put("hooksState", json.parseToJsonElement(hooksStateJson))
            put("positionMs", positionMs)
            put("playbackRate", playbackRate)
        })
    }

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float, isPaused: Boolean) {
        callHook("onProgressTick", buildJsonObject {
            put("hooksState", json.parseToJsonElement(hooksStateJson))
            put("positionMs", positionMs)
            put("playbackRate", playbackRate)
            put("isPaused", isPaused)
        })
    }

    override suspend fun onStop(positionMs: Long) {
        callHook("onStop", buildJsonObject {
            put("hooksState", json.parseToJsonElement(hooksStateJson))
            put("positionMs", positionMs)
        })
    }

    private suspend fun callHook(method: String, args: JsonObject) {
        runCatching { engine.callMethod(method, args.toString()) }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
