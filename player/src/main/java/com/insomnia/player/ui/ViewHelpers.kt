package com.insomnia.player.ui

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** Configure [PlayerView] options that apply to every platform (TV and Pad). */
@UnstableApi
fun configurePlayerViewDefaults(view: PlayerView) {
    view.useController = false
    view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
}
