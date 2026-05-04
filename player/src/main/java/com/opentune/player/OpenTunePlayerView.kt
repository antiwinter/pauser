package com.opentune.player

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Hide controller after this many ms without input (Media3 default is also 5s). */
private const val CONTROLLER_HIDE_AFTER_MS = 5_000

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
            view.setControllerShowTimeoutMs(CONTROLLER_HIDE_AFTER_MS)
            (view as? OpenTuneTvPlayerView)?.updatePlaybackStateIndicatorAttachment()
            onPlayerViewBound(view)
            view
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = useController
            view.setControllerShowTimeoutMs(CONTROLLER_HIDE_AFTER_MS)
            (view as? OpenTuneTvPlayerView)?.updatePlaybackStateIndicatorAttachment()
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
