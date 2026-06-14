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

enum class PlayingState {
    PLAYING,
    PAUSED,
    STOPPED,
}

/**
 * Entry playback state — seeded from [PlaybackSpec.state] at prepare time.
 * Persisted fields are written via [PlaybackSpec.updateEntryState].
 */
data class PlaybackState(
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val subtitleTrackId: String? = null,
    val audioTrackId: String? = null,
    val subtitleOffsetFraction: Float = 0f,
    val subtitleSizeScale: Float = 1f,
    val playingState: PlayingState = PlayingState.STOPPED,
)

object EntryStateKeys {
    const val POSITION_MS = "positionMs"
    const val SPEED = "speed"
    const val SUBTITLE_TRACK_ID = "subtitleTrackId"
    const val AUDIO_TRACK_ID = "audioTrackId"
    const val SUBTITLE_OFFSET_FRACTION = "subtitleOffsetFraction"
    const val SUBTITLE_SIZE_SCALE = "subtitleSizeScale"
    const val SERIES_PROGRESS = "seriesProgress"
    const val FAVORITE = "favorite"
    const val PLAYING_STATE = "playingState"
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
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val httpClient: okhttp3.OkHttpClient,
    val mediaCodecs: List<MediaCodecInfo> = emptyList(),
    val state: PlaybackState = PlaybackState(),
    val progressIntervalMs: Long = 10_000L,
    val updateEntryState: suspend (key: String, value: String?) -> Unit = { _, _ -> },
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
