package com.opentune.player

import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private const val SEEK_MS = 15_000L

@UnstableApi
@Composable
fun OpenTunePlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = true,
    onPlayerViewBound: (PlayerView) -> Unit = {},
) {
    AndroidView(
        factory = { context ->
            val view = LayoutInflater.from(context)
                .inflate(R.layout.opentune_player_view, null, false) as PlayerView
            view.player = player
            view.useController = useController
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            view.setControllerHideOnTouch(false)
            view.setControllerAutoShow(false)
            view.setShowPreviousButton(false)
            view.setShowNextButton(false)
            view.setShowRewindButton(false)
            view.setShowFastForwardButton(false)
            view.setShowShuffleButton(false)
            view.setShowVrButton(false)
            view.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE)
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = view.player ?: return@setOnKeyListener false
                val overlayVisible = view.isControllerFullyVisible
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    -> {
                        if (!overlayVisible) {
                            view.showController()
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!overlayVisible) {
                            val pos = p.currentPosition - SEEK_MS
                            p.seekTo(pos.coerceAtLeast(0L))
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!overlayVisible) {
                            val dur = p.duration
                            val delta = SEEK_MS
                            val target = p.currentPosition + delta
                            if (dur != C.TIME_UNSET && dur > 0) {
                                p.seekTo(target.coerceAtMost(dur))
                            } else {
                                p.seekTo(target)
                            }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        if (!overlayVisible) {
                            if (p.isPlaying) {
                                p.pause()
                            } else {
                                p.play()
                            }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
                }
            }
            onPlayerViewBound(view)
            view
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = useController
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
