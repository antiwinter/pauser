package com.opentune.player.engine

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlaybackSpec
import com.opentune.player.manager.PlayerMenuEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class PlaybackSurface(
    val exo: ExoPlayer,
    val bandwidthMbps: MutableFloatState,
    private val session: PlaybackSession,
) {
    val subtitleManager get() = session.subtitleManager

    /** All menu entries from all managers (for the settings menu). */
    val menuEntries: List<PlayerMenuEntry>
        get() = session.managers.flatMap { it.menuEntries }

    fun leaveSurface() {
        session.pause()
    }
}

@UnstableApi
@Composable
internal fun rememberPlaybackSurface(
    spec: PlaybackSpec,
    session: PlaybackSession,
): PlaybackSurface {
    val instanceKey = spec.sources.getOrNull(spec.state.sourceIndex)?.url ?: ""

    // Feed the session-owned SubtitleManager the current screen height for offset math.
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    session.subtitleManager.adjust.setScreenHeightPx(screenHeightPx)

    return key(instanceKey) {
        val exo = session.exo
        val bandwidthMbps = remember { mutableFloatStateOf(0f) }

        val engine = remember {
            PlaybackSurface(
                exo = exo,
                bandwidthMbps = bandwidthMbps,
                session = session,
            )
        }

        LaunchedEffect(Unit) {
            var lastTotal = 0L
            while (isActive) {
                delay(1_000)
                val mbps = BandwidthTracker.mbps
                bandwidthMbps.floatValue = mbps
                // OT_BW: per-second throughput timeline for play/seek/subtitle diagnosis.
                val total = BandwidthTracker.totalBytes
                val deltaKB = (total - lastTotal) / 1024
                lastTotal = total
                Log.i(
                    "OT_BW",
                    "mbps=%.2f deltaKB=%d totalMB=%.1f pos=%dms buffered=%dms state=%d".format(
                        mbps, deltaKB, total / 1_048_576f,
                        exo.currentPosition, exo.totalBufferedDuration, exo.playbackState,
                    ),
                )
            }
        }

        engine
    }
}
