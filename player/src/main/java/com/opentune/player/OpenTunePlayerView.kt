package com.opentune.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@UnstableApi
@Composable
fun OpenTunePlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = true,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                this.useController = useController
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setControllerHideOnTouch(false)
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (!isControllerFullyVisible) {
                            showController()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
