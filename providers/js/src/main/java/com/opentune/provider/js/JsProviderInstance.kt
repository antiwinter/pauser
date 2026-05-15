package com.opentune.provider.js

import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryList
import com.opentune.provider.EntryType
import com.opentune.provider.EntryUserData
import com.opentune.provider.ExternalUrl
import com.opentune.provider.OpenTunePlaybackHooks
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.StreamInfo
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Live provider instance backed by a dedicated QuickJS context.
 *
 * One context per server instance — contexts are not shared between instances
 * so JS state (instance credentials) is fully isolated.
 */
class JsProviderInstance(
    private val protocol: String,
    private val jsBundle: String,
    private val hostApis: HostApis,
    private val values: Map<String, String>,
    private val capabilities: PlatformCapabilities,
) : OpenTuneProviderInstance {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private lateinit var engine: QuickJsEngine
    private var initialized = false
    private val initMutex = Mutex()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private suspend fun ensureReady() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            engine = QuickJsEngine(hostApis)
            engine.init()
            engine.evalSnippet(JsProvider.HOST_BOOTSTRAP_JS)
            engine.evalBundle(jsBundle)

            /* init({ credentials, capabilities, deviceName, deviceId, clientVersion }) */
            val initArgs = buildJsonObject {
                put("credentials", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
                put("capabilities", buildJsonObject {
                    put("videoMime", kotlinx.serialization.json.JsonArray(
                        capabilities.videoMime.map { JsonPrimitive(it) },
                    ))
                    put("audioMime", kotlinx.serialization.json.JsonArray(
                        capabilities.audioMime.map { JsonPrimitive(it) },
                    ))
                    put("subtitleFormats", kotlinx.serialization.json.JsonArray(
                        capabilities.subtitleFormats.map { JsonPrimitive(it) },
                    ))
                    put("maxPixels", capabilities.maxPixels)
                })
            }
            engine.callMethod("init", initArgs.toString())
            initialized = true
        }
    }

    // ── OpenTuneProviderInstance ───────────────────────────────────────────

    override suspend fun listEntry(location: String?, startIndex: Int, limit: Int): EntryList {
        ensureReady()
        val args = buildJsonObject {
            if (location != null) put("location", location) else put("location", JsonNull)
            put("startIndex", startIndex)
            put("limit", limit)
        }
        val resultJson = engine.callMethod("listEntry", args.toString())
            ?: return EntryList(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson).jsonObject
        return EntryList(
            items = obj["items"]?.jsonArray?.mapNotNull { parseListItem(it.jsonObject) } ?: emptyList(),
            totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    override suspend fun search(scopeLocation: String, query: String): List<EntryInfo> {
        ensureReady()
        val args = buildJsonObject {
            put("scopeLocation", scopeLocation)
            put("query", query)
        }
        val resultJson = engine.callMethod("search", args.toString())
            ?: return emptyList()
        return json.parseToJsonElement(resultJson).jsonArray.mapNotNull { parseListItem(it.jsonObject) }
    }

    override suspend fun getDetail(itemRef: String): EntryDetail {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
        }
        val resultJson = engine.callMethod("getDetail", args.toString())
            ?: error("getDetail returned null")
        return parseDetailModel(json.parseToJsonElement(resultJson).jsonObject)
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

    private fun parseEntryType(raw: String?): EntryType = when (raw) {
        "Folder" -> EntryType.Folder
        "Series" -> EntryType.Series
        "Season" -> EntryType.Season
        "Episode" -> EntryType.Episode
        "Playable" -> EntryType.Playable
        else -> EntryType.Other
    }

    private fun parseListItem(obj: JsonObject): EntryInfo? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: id
        val typeRaw = obj["type"]?.jsonPrimitive?.content ?: obj["kind"]?.jsonPrimitive?.content
        val type = parseEntryType(typeRaw)
        val cover = obj["cover"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: obj["coverUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val ud = obj["userData"]?.takeIf { it !is JsonNull }?.jsonObject
        return EntryInfo(
            id = id,
            title = title,
            type = type,
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
        )
    }

    private fun parseDetailModel(obj: JsonObject): EntryDetail {
        val logo = obj["logo"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: obj["logoUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val backdrop = obj["backdrop"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: obj["backdropImages"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: emptyList()
        val streams = (obj["streams"]?.jsonArray ?: obj["mediaStreams"]?.jsonArray)?.map { s ->
            val so = s.jsonObject
            StreamInfo(
                index = so["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                type = so["type"]?.jsonPrimitive?.content ?: "",
                codec = so["codec"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                title = so["title"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: so["displayTitle"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                language = so["language"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                isDefault = so["isDefault"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                isForced = so["isForced"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        } ?: emptyList()
        val externalUrls = obj["externalUrls"]?.jsonArray?.mapNotNull { u ->
            val uo = u.jsonObject
            val name = uo["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = uo["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ExternalUrl(name, url)
        } ?: emptyList()
        val providerIds = obj["providerIds"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val isMedia = obj["isMedia"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: obj["canPlay"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: false
        val rating = obj["rating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toFloatOrNull()
            ?: obj["communityRating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toFloatOrNull()
        val year = obj["year"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
            ?: obj["productionYear"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
        return EntryDetail(
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            overview = obj["overview"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            logo = logo,
            backdrop = backdrop,
            isMedia = isMedia,
            rating = rating,
            bitrate = obj["bitrate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            externalUrls = externalUrls,
            year = year,
            providerIds = providerIds,
            streams = streams,
            etag = obj["etag"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
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

        val title = obj["title"]?.jsonPrimitive?.content
            ?: obj["displayTitle"]?.jsonPrimitive?.content
            ?: ""

        return PlaybackSpec(
            url = requireNotNull(url) { "JS provider returned null URL in getPlaybackSpec" },
            headers = headers,
            mimeType = mimeType,
            title = title,
            durationMs = obj["durationMs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull(),
            hooks = hooks,
            subtitleTracks = subtitles,
        )
    }
}

/**
 * Delegates playback hook calls back into the JS provider instance.
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

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) {
        callHook("onProgressTick", buildJsonObject {
            put("hooksState", json.parseToJsonElement(hooksStateJson))
            put("positionMs", positionMs)
            put("playbackRate", playbackRate)
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
