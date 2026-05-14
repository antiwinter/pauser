package com.opentune.provider

interface OpenTunePlaybackHooks {
    /** If 0, the shell does not run a progress tick loop. */
    fun progressIntervalMs(): Long

    suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float)
    suspend fun onProgressTick(positionMs: Long, playbackRate: Float)
    suspend fun onStop(positionMs: Long)

    /**
     * Called by the player after [onStop] and after ExoPlayer is released.
     * Use this to close resources that must outlive playback (e.g. SMB TCP session).
     * Default is a no-op; only providers that hold open connections need to override.
     */
    fun onDispose() {}
}

data class PlaybackSpec(
    /** HTTP(S) stream URL. Always non-null; SMB uses a loopback URL from [OpenTuneServer]. */
    val url: String,
    /** Default request headers for [url] and for external subtitle HTTP loads. */
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val title: String,
    val durationMs: Long?,
    val hooks: OpenTunePlaybackHooks,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
)
