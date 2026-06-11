package com.opentune.player

import com.opentune.player.engine.PlaybackSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Media codec information included in [PlaybackSpec] and [com.opentune.content.contract.EntryInfo].
 * info[0].codec is always the video codec (used on detail screen).
 */
@Serializable
data class MediaCodecInfo(
    val codec: String,
    val bitDepth: Int? = null,
    val profile: String? = null,
)

/**
 * Display information for the player UI overlay.
 * Set from [com.opentune.content.contract.EntryInfo] by content layer.
 */
data class PlaybackDisplayInfo(
    val title: String = "",
    val bitrate: Int? = null,
)

interface OpenTunePlaybackHooks {
    fun progressIntervalMs(): Long
    suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float)
    suspend fun onProgressTick(positionMs: Long, playbackRate: Float, isPaused: Boolean = false)
    suspend fun onStop(positionMs: Long)
    fun onDispose() {}
}

data class SubtitleTrack(
    val trackId: String,
    val label: String,
    val language: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val externalRef: String?,
)

data class PlaybackSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val hooks: OpenTunePlaybackHooks,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val httpClient: okhttp3.OkHttpClient,
    val mediaCodecs: List<MediaCodecInfo> = emptyList(),
)

/**
 * Contract that player surfaces (TvPlayerSurface, PadPlayerSurface) need from the
 * host controller. Only `:player` types — no dependency on `:content:ui` or `:content:contract`.
 */
interface PlayerSurfaceController {
    val playbackSession: PlaybackSession
    val hasNextVideoFlow: StateFlow<Boolean>
    val displayInfoFlow: StateFlow<PlaybackDisplayInfo>
    fun requestNextVideo()
}
