package com.opentune.content.epcache

import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.PlaybackMimeTypes
import com.opentune.content.contract.QueryOptions
import com.opentune.content.contract.StreamRegistrarHolder
import com.opentune.player.PlaybackSource
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackState
import com.opentune.player.PlayingState
import com.opentune.player.SubtitleTrack
import com.opentune.storage.EntryStateStore
import com.opentune.storage.StorageBindingsHolder

private val subtitleExts = setOf(".srt", ".ass", ".ssa", ".vtt", ".sub")

internal suspend fun enrichSpec(
    sources: List<PlaybackSource>,
    info: EntryInfo,
    startMs: Long,
    endpointId: String,
    httpClient: okhttp3.OkHttpClient,
    progressIntervalMs: Long,
    updateEntryState: suspend (String, String?) -> Unit,
): PlaybackSpec {
    val subtitlePrefs = StorageBindingsHolder.get().appConfigStore.loadSubtitlePrefs()

    val enrichedSources = sources.map { src ->
        src.copy(mimeType = src.mimeType ?: PlaybackMimeTypes.fromUrl(src.url))
    }

    val state = PlaybackState(
        sourceIndex = getInheritedValue(endpointId, info, "sourceIndex") as? Int ?: 0,
        positionMs = startMs,
        speed = getInheritedValue(endpointId, info, "playbackSpeed") as? Float ?: 1f,
        subtitleTrackId = getInheritedValue(endpointId, info, "selectedSubtitleTrackId") as? String,
        audioTrackId = getInheritedValue(endpointId, info, "selectedAudioTrackId") as? String,
        subtitleOffsetFraction = subtitlePrefs.offsetFraction,
        subtitleSizeScale = subtitlePrefs.sizeScale,
        playingState = PlayingState.STOPPED,
    )
    return PlaybackSpec(
        sources = enrichedSources,
        httpClient = httpClient,
        state = state,
        progressIntervalMs = progressIntervalMs,
        updateEntryState = updateEntryState,
    )
}

internal suspend fun constructStreamSources(
    delegate: EndpointClient,
    info: EntryInfo,
    activeStreamUrls: java.util.concurrent.ConcurrentHashMap<String, List<String>>,
): List<PlaybackSource> {
    val registrar = StreamRegistrarHolder.get()
    val videoUrl = registrar.registerStream(delegate, info.ref)
    val subtitleTracks = scanSidecarSubtitles(delegate, info)

    activeStreamUrls[info.ref] = listOf(videoUrl) + subtitleTracks.mapNotNull { it.externalRef }
    return listOf(PlaybackSource(url = videoUrl, subtitleTracks = subtitleTracks))
}

internal suspend fun scanSidecarSubtitles(
    delegate: EndpointClient,
    info: EntryInfo,
): List<SubtitleTrack> {
    val parentLocation = info.parentRef ?: info.ref.substringBeforeLast('/')
    if (parentLocation.isEmpty()) return emptyList()

    val siblings = try {
        delegate.listEntry(parentLocation, 0, 200, QueryOptions())
    } catch (_: Exception) {
        return emptyList()
    }

    val videoStem = info.filename?.substringBeforeLast('.')
        ?: info.ref.substringAfterLast('/').substringBeforeLast('.')
    val registrar = StreamRegistrarHolder.get()

    return siblings.items.filter { sibling ->
        sibling.filename?.lowercase()?.let { name ->
            subtitleExts.any { name.endsWith(it) }
        } ?: false
    }.mapNotNull { sibling ->
        val stem = sibling.filename?.substringBeforeLast('.') ?: return@mapNotNull null
        if (stem != videoStem) return@mapNotNull null
        val streamUrl = registrar.registerStream(delegate, sibling.ref)
        SubtitleTrack(
            trackId = sibling.ref,
            label = sibling.filename ?: sibling.ref,
            language = null,
            isDefault = false,
            isForced = false,
            externalRef = streamUrl,
        )
    }
}

internal suspend fun getInheritedValue(endpointId: String, info: EntryInfo, attribute: String): Any? {
    val store = StorageBindingsHolder.get().entryStateStore
    for (ref in listOf(info.ref, info.parentRef, info.seriesRef)) {
        if (ref == null) continue
        val row = store.get(endpointId, ref) ?: continue
        val value = when (attribute) {
            "playbackSpeed" -> row.playbackSpeed.takeUnless { it == 1f }
            "selectedSubtitleTrackId" -> row.selectedSubtitleTrackId?.takeIf { it.isNotEmpty() }
            "selectedAudioTrackId" -> row.selectedAudioTrackId?.takeIf { it.isNotEmpty() }
            else -> null
        }
        if (value != null) return value
    }
    return null
}
