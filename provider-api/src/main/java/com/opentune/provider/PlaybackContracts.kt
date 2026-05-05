package com.opentune.provider

import androidx.media3.exoplayer.source.MediaSource

/**
 * Source-specific playback side effects (session reporting, etc.).
 * Implementations must not reference ExoPlayer or Media3 — the TV shell passes
 * primitive timing and rate only.
 */
interface OpenTunePlaybackHooks {
    /** If 0, the shell does not run a progress tick loop. */
    fun progressIntervalMs(): Long

    suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float)
    suspend fun onProgressTick(positionMs: Long, playbackRate: Float)
    suspend fun onStop(positionMs: Long)
}

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
    val durationMs: Long?,
    val audioFallbackOnly: Boolean,
    val hooks: OpenTunePlaybackHooks,
    /** When non-null, shown as a top overlay (e.g. SMB "audio not supported" after in-place retry). */
    val audioDecodeUnsupportedBanner: String? = null,
    /** ExoPlayer seek target when the screen opens (route hint and/or local resume store). */
    val initialPositionMs: Long = 0L,
    /** Invoked when the player screen is torn down (e.g. close SMB session after playback). */
    val onPlaybackDispose: () -> Unit = {},
)
