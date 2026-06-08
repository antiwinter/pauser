package com.opentune.player

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract that player surfaces (TvPlayerSurface, PadPlayerSurface) need from the
 * host controller. Only `:player` types — no dependency on `:content:ui` or `:content:contract`.
 */
interface PlayerSurfaceController {
    val currentSpec: PlaybackSpec?
    val storageCtx: PlaybackStorageContext?
    val startMs: Long
    val exoPlayer: ExoPlayer
    val hasNextVideoFlow: StateFlow<Boolean>
    fun requestNextVideo()
}
