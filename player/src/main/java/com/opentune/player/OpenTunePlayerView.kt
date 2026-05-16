package com.opentune.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.util.Log
import androidx.compose.foundation.background
import androidx.media3.ui.CaptionStyleCompat
import android.graphics.Color as AndroidColor
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

/** Hide controller after this many ms without input. We drive this ourselves so
 * programmatic hideController() fires immediately with no shrink animation. */
internal const val CONTROLLER_HIDE_AFTER_MS = 5_000

@UnstableApi
@Composable
fun OpenTunePlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = true,
    onPlayerViewBound: (PlayerView) -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onBack: () -> Unit = {},
    onKey: ((KeyEvent) -> Boolean)? = null,
    onControllerVisibilityChanged: ((Boolean) -> Unit)? = null,
    subtitleTranslationYPx: Float = 0f,
    subtitleSizeScale: Float = 1f,
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
            // -1 = never auto-hide; we drive the 5s timeout ourselves via LaunchedEffect
            // so hideController() fires immediately (no two-phase shrink animation).
            view.setControllerShowTimeoutMs(-1)
            (view as? OpenTuneTvPlayerView)?.updatePlaybackStateIndicatorAttachment()
            onPlayerViewBound(view)
            view
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = useController
            view.setControllerShowTimeoutMs(-1)
            (view as? OpenTuneTvPlayerView)?.also { tv ->
                tv.updatePlaybackStateIndicatorAttachment()
                tv.openMenuCallback = onOpenMenu
                tv.onBack = onBack
                tv.onKey = onKey
                tv.onControllerVisible = onControllerVisibilityChanged
                val sv = tv.subtitleView
                if (sv == null) {
                    Log.w("OT_Subtitle", "update: subtitleView is null — cannot apply translation/scale")
                } else {
                    Log.d("OT_Subtitle", "update: subtitleView translationY=$subtitleTranslationYPx sizeScale=$subtitleSizeScale")
                    sv.translationY = subtitleTranslationYPx
                    sv.scaleX = subtitleSizeScale
                    sv.scaleY = subtitleSizeScale
                    sv.setStyle(
                        CaptionStyleCompat(
                            CaptionStyleCompat.DEFAULT.foregroundColor,
                            AndroidColor.TRANSPARENT, // no background capsule
                            AndroidColor.TRANSPARENT, // no window color
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            AndroidColor.BLACK,
                            null, // default typeface
                        )
                    )
                    val hPad = (16 * view.resources.displayMetrics.density).toInt()
                    sv.setPadding(hPad, 0, hPad, 0)
                    sv.requestLayout()
                    sv.invalidate()
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
