package com.insomnia.player.engine

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
import com.insomnia.player.PlaybackSpec
import com.insomnia.player.manager.PlayerMenuEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

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
    session.subtitleManager.cc.setScreenHeightPx(screenHeightPx)

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
            while (isActive) {
                delay(1_000)
                val mbps = BandwidthTracker.mbps
                bandwidthMbps.floatValue = mbps
            }
        }

        engine
    }
}
