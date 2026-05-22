package com.opentune.content.contract

interface OpenTunePlaybackHooks {
    fun progressIntervalMs(): Long
    suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float)
    suspend fun onProgressTick(positionMs: Long, playbackRate: Float, isPaused: Boolean = false)
    suspend fun onStop(positionMs: Long)
    fun onDispose() {}
}

data class PlaybackSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val title: String,
    val durationMs: Long?,
    val hooks: OpenTunePlaybackHooks,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val httpClient: okhttp3.OkHttpClient,
)
