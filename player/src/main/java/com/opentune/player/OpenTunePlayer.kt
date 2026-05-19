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
import com.opentune.storage.AppConfigStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore

/**
 * Platform selector. Reads [UiModeManager] once and routes to [TvPlayer] or [PadPlayer].
 * Argument list is identical to both shells so [PlayerRoute] can call this without any
 * conditional logic.
 */
@UnstableApi
@Composable
fun OpenTunePlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    entryStateStore: EntryStateStore,
    entryStateKey: EntryStateKey,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
    appConfigStore: AppConfigStore? = null,
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
            entryStateStore = entryStateStore,
            entryStateKey = entryStateKey,
            onExit = onExit,
            initialSubtitleTrackId = initialSubtitleTrackId,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
            initialSubtitleSizeScale = initialSubtitleSizeScale,
            appConfigStore = appConfigStore,
        )
    } else {
        PadPlayer(
            spec = spec,
            startMs = startMs,
            entryStateStore = entryStateStore,
            entryStateKey = entryStateKey,
            onExit = onExit,
            initialSubtitleTrackId = initialSubtitleTrackId,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
            initialSubtitleSizeScale = initialSubtitleSizeScale,
            appConfigStore = appConfigStore,
        )
    }
}
