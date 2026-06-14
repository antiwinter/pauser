package com.opentune.player

/**
 * One-shot snapshot passed to [com.opentune.player.engine.PlaybackSession.prepare].
 * The session copies values into per-field flows; callers outside `:player` build this,
 * surfaces and managers read flows only.
 */
data class PlaybackState(
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val subtitleTrackId: String? = null,
    val audioTrackId: String? = null,
    val subtitleOffsetFraction: Float = 0f,
    val subtitleSizeScale: Float = 1f,
)
