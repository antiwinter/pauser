package com.opentune.player

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.opentune.player.ui.pad.PadPlayer
import com.opentune.player.ui.tv.TvPlayer
import com.opentune.provider.PlaybackSpec

@UnstableApi
@Composable
fun OpenTunePlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
) {
    val context = LocalContext.current
    val isTv = remember {
        val um = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    if (isTv) {
        TvPlayer(
            spec = spec,
            startMs = startMs,
            onExit = onExit,
            initialSubtitleTrackId = initialSubtitleTrackId,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
            initialSubtitleSizeScale = initialSubtitleSizeScale,
        )
    } else {
        PadPlayer(
            spec = spec,
            startMs = startMs,
            onExit = onExit,
            initialSubtitleTrackId = initialSubtitleTrackId,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
            initialSubtitleSizeScale = initialSubtitleSizeScale,
        )
    }
}
