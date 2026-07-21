package com.insomnia.provider.js

import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryUserData
import com.insomnia.player.MediaCodecInfo
import com.insomnia.player.PlaybackSource
import com.insomnia.player.SubtitleTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Decodes [EntryInfo] / [PlaybackSource] from the JSON objects JS providers return.
 * Shared between [JsClient] (single-shot listEntry/getPlaybackSources responses) and
 * [EngineHostApis] (progressive `notification.send` payloads whose `result.data` is a
 * list of entry objects in the same shape).
 */
internal object EntryInfoCodec {

    fun parseEntry(obj: JsonObject): EntryInfo? {
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
            actors = obj["actors"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            directors = obj["directors"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            areas = obj["areas"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            languages = obj["languages"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
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
            quality = obj["quality"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            filename = obj["filename"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            sources = obj["sources"]?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { el -> parseSource(el.jsonObject).takeIf { it.url.isNotEmpty() } },
        )
    }

    fun parseSource(obj: JsonObject): PlaybackSource {
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
