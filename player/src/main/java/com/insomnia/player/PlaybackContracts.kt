package com.insomnia.player

import kotlinx.serialization.Serializable
import com.insomnia.player.engine.PlaybackSession
import com.insomnia.player.manager.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Display information for the player UI overlay.
 * Set from [com.insomnia.content.contract.EntryInfo] by content layer.
 */
data class PlaybackDisplayInfo(
    val title: String = "",
)

@Serializable
data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val mediaCodecs: List<MediaCodecInfo> = emptyList(),
)

@Serializable
data class SubtitleTrack(
    val trackId: String,
    val label: String,
    val language: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val externalRef: String? = null,
)

@Serializable
data class MediaCodecInfo(
    val codec: String,
    val bitDepth: Int? = null,
    val profile: String? = null,
    // Video bitrate lives on the video codec entry (mediaCodecs[0]); the InfoOverlay reads it there.
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
    val sourceIndex: Int = 0,
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val subtitleTrackId: String? = null,
    val audioTrackId: String? = null,
    val subtitleOffsetFraction: Float = 0f,
    val subtitleSizeScale: Float = 1f,
    val playingState: PlayingState = PlayingState.STOPPED,
)

object EntryStateKeys {
    const val SOURCE_INDEX = "sourceIndex"
    const val POSITION_MS = "positionMs"
    const val SPEED = "speed"
    const val SUBTITLE_TRACK_ID = "subtitleTrackId"
    const val AUDIO_TRACK_ID = "audioTrackId"
    const val SUBTITLE_OFFSET_FRACTION = "subtitleOffsetFraction"
    const val SUBTITLE_SIZE_SCALE = "subtitleSizeScale"
    const val FAVORITE = "favorite"
    const val PLAYING_STATE = "playingState"
}

data class PlaybackSpec(
    val sources: List<PlaybackSource>,
    val httpClient: okhttp3.OkHttpClient,
    val state: PlaybackState = PlaybackState(),
    val progressIntervalMs: Long = 10_000L,
    val updateEntryState: suspend (key: String, value: String?) -> Unit = { _, _ -> },
) {
    fun withSourceIndex(index: Int): PlaybackSpec =
        copy(state = state.copy(sourceIndex = index.coerceIn(0, maxOf(0, sources.size - 1))))
}

/**
 * Contract that player surfaces (TvPlayerSurface, PadPlayerSurface) need from the
 * host controller. Only `:player` and `:content:contract` types — no dependency on `:content:ui`.
 */
interface PlayerSurfaceController {
    val playbackSession: PlaybackSession
    val hasNextVideoFlow: StateFlow<Boolean>
    val displayInfoFlow: StateFlow<PlaybackDisplayInfo>
    val sourceManagerFlow: StateFlow<SourceManager?> get() = MutableStateFlow(null)
    fun requestNextVideo()
}
