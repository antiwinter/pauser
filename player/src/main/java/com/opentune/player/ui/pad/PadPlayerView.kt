package com.opentune.player.ui.pad

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.opentune.player.ui.applySubtitleStyle
import com.opentune.player.ui.configurePlayerViewDefaults

/** Minimal [PlayerView] subclass for the Pad platform. No custom key dispatch needed — the
 * Compose layer handles touch via [pointerInput] on the surrounding [Box]. */
@UnstableApi
class OpenTunePadPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr)

@UnstableApi
@Composable
internal fun PadPlayerView(
    player: ExoPlayer,
    subtitleTranslationYPx: Float = 0f,
    subtitleSizeScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            OpenTunePadPlayerView(context).also { view ->
                configurePlayerViewDefaults(view)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            applySubtitleStyle(view, subtitleTranslationYPx, subtitleSizeScale)
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
