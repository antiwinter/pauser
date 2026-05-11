package com.opentune.provider

interface OpenTunePlaybackHooks {
    /** If 0, the shell does not run a progress tick loop. */
    fun progressIntervalMs(): Long

    suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float)
    suspend fun onProgressTick(positionMs: Long, playbackRate: Float)
    suspend fun onStop(positionMs: Long)
}

object NoOpPlaybackHooks : OpenTunePlaybackHooks {
    override fun progressIntervalMs(): Long = 0L
    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onStop(positionMs: Long) = Unit
}

data class PlaybackUrlSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
)

data class PlaybackSpec(
    /** HTTP(S) sources. Null for providers that supply their own data pipeline (e.g. SMB). */
    val urlSpec: PlaybackUrlSpec? = null,
    /**
     * For Android-only providers that cannot be expressed as a URL (e.g. SMB via SmbDataSource).
     * Typed as `() -> Any` so provider-api has zero Media3 imports; the player module casts to
     * `() -> MediaSource`.
     */
    val customMediaSourceFactory: (() -> Any)? = null,
    val displayTitle: String,
    val durationMs: Long?,
    val hooks: OpenTunePlaybackHooks,
    val initialPositionMs: Long = 0L,
    val onPlaybackDispose: () -> Unit = {},
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** HTTP headers added when loading external subtitle URLs (e.g. Emby auth token). */
    val subtitleHeaders: Map<String, String> = emptyMap(),
)
