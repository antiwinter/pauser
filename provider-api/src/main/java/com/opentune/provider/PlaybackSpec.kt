package com.opentune.provider

import androidx.media3.exoplayer.source.MediaSource

fun interface OpenTuneMediaSourceFactory {
    fun create(): MediaSource
}

data class PlaybackSpec(
    val mediaSourceFactory: OpenTuneMediaSourceFactory,
    /**
     * When [audioFallbackOnly], passed to the audio-decode fallback listener as a fresh
     * [androidx.media3.exoplayer.source.MediaSource] (typically same construction as the main stream).
     */
    val audioFallbackFactory: OpenTuneMediaSourceFactory?,
    val displayTitle: String,
    val resumeKey: String,
    val durationMs: Long?,
    val audioFallbackOnly: Boolean,
    val hooks: OpenTunePlaybackHooks,
    /** When non-null, shown as a top overlay (e.g. SMB “audio not supported” after in-place retry). */
    val audioDecodeUnsupportedBanner: String? = null,
    /** ExoPlayer seek target when the screen opens (route hint and/or local resume store). */
    val initialPositionMs: Long = 0L,
    /** Invoked when the player screen is torn down (e.g. close SMB session after playback). */
    val onPlaybackDispose: () -> Unit = {},
)
