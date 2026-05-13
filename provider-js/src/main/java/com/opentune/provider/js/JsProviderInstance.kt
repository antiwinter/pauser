package com.opentune.provider.js

import com.opentune.provider.BrowsePageResult
import com.opentune.provider.CodecCapabilities
import com.opentune.provider.ExternalUrl
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.MediaStreamInfo
import com.opentune.provider.MediaUserData
import com.opentune.provider.OpenTunePlaybackHooks
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.PlaybackUrlSpec
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    private val capabilities: CodecCapabilities,
) : OpenTuneProviderInstance {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private lateinit var engine: QuickJsEngine
    private var initialized = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private suspend fun ensureReady() {
        if (initialized) return
        engine = QuickJsEngine(hostApis)
        engine.init()
        engine.evalSnippet(JsProvider.HOST_BOOTSTRAP_JS)
        engine.evalBundle(jsBundle)

        /* init({ credentials, capabilities, deviceName, deviceId, clientVersion }) */
        val initArgs = buildJsonObject {
            put("credentials", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
            put("capabilities", buildJsonObject {
                put("videoMimes", kotlinx.serialization.json.JsonArray(
                    capabilities.supportedVideoMimeTypes.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
                put("audioMimes", kotlinx.serialization.json.JsonArray(
                    capabilities.supportedAudioMimeTypes.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
                put("subtitleFormats", kotlinx.serialization.json.JsonArray(
                    capabilities.supportedSubtitleFormats.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
                put("maxVideoPixels", capabilities.maxVideoPixels)
            })
            put("deviceName", hostApis.deviceName)
            put("deviceId", hostApis.deviceId)
            put("clientVersion", hostApis.clientVersion)
        }
        engine.callMethod("init", initArgs.toString())
        initialized = true
    }

    // ── OpenTuneProviderInstance ───────────────────────────────────────────

    override suspend fun loadBrowsePage(location: String?, startIndex: Int, limit: Int): BrowsePageResult {
        ensureReady()
        val args = buildJsonObject {
            if (location != null) put("location", location) else put("location", JsonNull)
            put("startIndex", startIndex)
            put("limit", limit)
        }
        val resultJson = engine.callMethod("loadBrowsePage", args.toString())
            ?: return BrowsePageResult(emptyList(), 0)
        val obj = json.parseToJsonElement(resultJson).jsonObject
        return BrowsePageResult(
            items      = obj["items"]?.jsonArray?.mapNotNull { parseListItem(it.jsonObject) } ?: emptyList(),
            totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    override suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem> {
        ensureReady()
        val args = buildJsonObject {
            put("scopeLocation", scopeLocation)
            put("query", query)
        }
        val resultJson = engine.callMethod("searchItems", args.toString())
            ?: return emptyList()
        return json.parseToJsonElement(resultJson).jsonArray.mapNotNull { parseListItem(it.jsonObject) }
    }

    override suspend fun loadDetail(itemRef: String): MediaDetailModel {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
        }
        val resultJson = engine.callMethod("loadDetail", args.toString())
            ?: error("loadDetail returned null")
        return parseDetailModel(json.parseToJsonElement(resultJson).jsonObject)
    }

    override suspend fun resolvePlayback(itemRef: String, startMs: Long): PlaybackSpec {
        ensureReady()
        val args = buildJsonObject {
            put("itemRef", itemRef)
            put("startMs", startMs)
        }
        val resultJson = engine.callMethod("resolvePlayback", args.toString())
            ?: error("resolvePlayback returned null")
        return parsePlaybackSpec(json.parseToJsonElement(resultJson).jsonObject)
    }

    // ── Parsers ────────────────────────────────────────────────────────────

    private fun parseListItem(obj: JsonObject): MediaListItem? {
        val id    = obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: id
        val kind  = when (obj["kind"]?.jsonPrimitive?.content) {
            "Folder"   -> MediaEntryKind.Folder
            "Series"   -> MediaEntryKind.Series
            "Season"   -> MediaEntryKind.Season
            "Episode"  -> MediaEntryKind.Episode
            "Playable" -> MediaEntryKind.Playable
            else       -> MediaEntryKind.Other
        }
        val coverUrl = obj["coverUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val cover    = if (coverUrl != null) MediaArt.Http(coverUrl) else MediaArt.None
        val ud       = obj["userData"]?.takeIf { it !is JsonNull }?.jsonObject
        return MediaListItem(
            id             = id,
            title          = title,
            kind           = kind,
            cover          = cover,
            userData       = ud?.let {
                MediaUserData(
                    positionMs = it["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    isFavorite = it["isFavorite"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    played     = it["played"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                )
            },
            originalTitle  = obj["originalTitle"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            genres         = obj["genres"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            communityRating= obj["communityRating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toFloatOrNull(),
            studios        = obj["studios"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            etag           = obj["etag"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            indexNumber    = obj["indexNumber"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    private fun parseDetailModel(obj: JsonObject): MediaDetailModel {
        val logoUrl = obj["logoUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val logo    = if (logoUrl != null) MediaArt.Http(logoUrl) else MediaArt.None
        val backdrops = obj["backdropImages"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val streams   = obj["mediaStreams"]?.jsonArray?.map { s ->
            val so = s.jsonObject
            MediaStreamInfo(
                index        = so["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                type         = so["type"]?.jsonPrimitive?.content ?: "",
                codec        = so["codec"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                displayTitle = so["displayTitle"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                language     = so["language"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                isDefault    = so["isDefault"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                isForced     = so["isForced"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        } ?: emptyList()
        val externalUrls = obj["externalUrls"]?.jsonArray?.mapNotNull { u ->
            val uo = u.jsonObject
            val name = uo["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url  = uo["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ExternalUrl(name, url)
        } ?: emptyList()
        val providerIds = obj["providerIds"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        return MediaDetailModel(
            title           = obj["title"]?.jsonPrimitive?.content ?: "",
            overview        = obj["overview"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            logo            = logo,
            backdropImages  = backdrops,
            canPlay         = obj["canPlay"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            communityRating = obj["communityRating"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toFloatOrNull(),
            bitrate         = obj["bitrate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            externalUrls    = externalUrls,
            productionYear  = obj["productionYear"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            providerIds     = providerIds,
            mediaStreams     = streams,
            etag            = obj["etag"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        )
    }

    private fun parsePlaybackSpec(obj: JsonObject): PlaybackSpec {
        val urlSpecObj = obj["urlSpec"]?.takeIf { it !is JsonNull }?.jsonObject
        val urlSpec = urlSpecObj?.let {
            PlaybackUrlSpec(
                url     = it["url"]?.jsonPrimitive?.content ?: error("urlSpec.url missing"),
                headers = it["headers"]?.takeIf { h -> h !is JsonNull }?.jsonObject
                    ?.mapValues { e -> e.value.jsonPrimitive.content } ?: emptyMap(),
                mimeType = it["mimeType"]?.takeIf { m -> m !is JsonNull }?.jsonPrimitive?.content,
            )
        }
        val subtitles = obj["subtitleTracks"]?.jsonArray?.mapNotNull { s ->
            val so = s.jsonObject
            val trackId = so["trackId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SubtitleTrack(
                trackId  = trackId,
                label    = so["label"]?.jsonPrimitive?.content ?: trackId,
                language = so["language"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                isDefault = so["isDefault"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                isForced  = so["isForced"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                externalRef = so["externalRef"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
        } ?: emptyList()
        val subtitleHeaders = obj["subtitleHeaders"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.mapValues { e -> e.value.jsonPrimitive.content } ?: emptyMap()

        /* Hooks: JS Emby instance keeps state for progress reporting.
           We implement the hooks as additional JS calls into the same instance. */
        val hooks = JsPlaybackHooks(
            engine       = engine,
            hooksStateJson = obj["hooksState"]?.toString() ?: "{}",
        )

        return PlaybackSpec(
            urlSpec              = urlSpec,
            customMediaSourceFactory = null, /* JS providers are HTTP-only */
            displayTitle         = obj["displayTitle"]?.jsonPrimitive?.content ?: "",
            durationMs           = obj["durationMs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull(),
            hooks                = hooks,
            subtitleTracks       = subtitles,
            subtitleHeaders      = subtitleHeaders,
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
