package com.opentune.player

import com.opentune.player.engine.PlaybackSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract that player surfaces (TvPlayerSurface, PadPlayerSurface) need from the
 * host controller. Only `:player` types — no dependency on `:content:ui` or `:content:contract`.
 */
interface PlayerSurfaceController {
    val playbackSession: PlaybackSession
    val hasNextVideoFlow: StateFlow<Boolean>
    fun requestNextVideo()
}
