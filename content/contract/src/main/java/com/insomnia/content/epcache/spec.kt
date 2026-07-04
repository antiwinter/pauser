package com.insomnia.content.epcache

import android.net.Uri
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.FileRelay
import com.insomnia.content.contract.FileRelayRecipe
import com.insomnia.content.contract.PlaybackMimeTypes
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SERVER_PORT
import com.insomnia.content.contract.UrlRelayRecipe
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlaybackSpec
import com.insomnia.player.PlaybackState
import com.insomnia.player.PlayingState
import com.insomnia.player.SubtitleTrack
import com.insomnia.storage.EntryStateStore
import com.insomnia.storage.StorageBindingsHolder

private val subtitleExts = setOf(".srt", ".ass", ".ssa", ".vtt", ".sub")

/**
 * Deterministic file-service relay URL (`fs` recipe, token `"fs"`). `ep`/`ref` params carry
 * `endpointId`/`itemRef` — no per-URL registration — so CacheDataSource keys are stable across
 * seeks/sessions.
 *
 * `Uri.encode` (not `URLEncoder.encode`): Ktor's query decoding, like `Uri.decode`, only
 * interprets `%xx` and leaves `+` literal, so `URLEncoder`'s `+`-for-space would break SMB
 * paths with spaces (see content/ui/.../Routes.kt:15-17).
 */
private fun streamUrl(endpointId: String, itemRef: String, cached: Boolean): String =
    "http://127.0.0.1:${SERVER_PORT}/relay/fs?ep=" + Uri.encode(endpointId) + "&ref=" + Uri.encode(itemRef) +
        if (cached) "&cached=true" else ""

/** Wrap a progressive HTTP URL so it flows through sr (proxy on the sr→origin leg, RAM cache). */
private fun relayUrl(endpointId: String, originalUrl: String): String =
    "http://127.0.0.1:${SERVER_PORT}/relay/url?ep=" + Uri.encode(endpointId) + "&url=" +
        Uri.encode(originalUrl) + "&cached=true"

private val relayPrefix = "http://127.0.0.1:${SERVER_PORT}/relay/"
private const val HLS_MIME = "application/vnd.apple.mpegurl"

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
        val mt = src.mimeType ?: PlaybackMimeTypes.fromUrl(src.url)
        // HLS stays direct (manifest URL-rewriting breaks relative segment URLs); URLs already
        // pointing at sr (fs/url/spider token) are left as-is. Other progressive HTTP URLs are
        // wrapped through /relay/url so the proxy + RAM cache apply on the sr→origin leg.
        val url = if (mt == HLS_MIME || src.url.startsWith(relayPrefix)) {
            src.url
        } else {
            UrlRelayRecipe.ensureRegistered()
            relayUrl(endpointId, src.url)
        }
        src.copy(mimeType = mt, url = url)
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
    activeStreamRefs: java.util.concurrent.ConcurrentHashMap<String, List<String>>,
): List<PlaybackSource> {
    // Probe before advertising a URL: only endpoints that actually provide a ProviderStream get a relay address.
    FileRelayRecipe.ensureRegistered()
    if (!FileRelay.ensureOpen(delegate.endpointId, info.ref) { delegate.openStream(info.ref) }) {
        return emptyList()
    }
    val videoUrl = streamUrl(delegate.endpointId, info.ref, cached = true)
    val subtitleTracks = scanSidecarSubtitles(delegate, info)

    activeStreamRefs[info.ref] = listOf(info.ref) + subtitleTracks.map { it.trackId }
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

    return siblings.items.filter { sibling ->
        sibling.filename?.lowercase()?.let { name ->
            subtitleExts.any { name.endsWith(it) }
        } ?: false
    }.mapNotNull { sibling ->
        val stem = sibling.filename?.substringBeforeLast('.') ?: return@mapNotNull null
        if (stem != videoStem) return@mapNotNull null
        val url = streamUrl(delegate.endpointId, sibling.ref, cached = false)
        SubtitleTrack(
            trackId = sibling.ref,
            label = sibling.filename ?: sibling.ref,
            language = null,
            isDefault = false,
            isForced = false,
            externalRef = url,
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
