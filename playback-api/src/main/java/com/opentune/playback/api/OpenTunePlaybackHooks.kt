package com.opentune.playback.api

/**
 * Source-specific playback side effects (Emby session reporting, etc.).
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
