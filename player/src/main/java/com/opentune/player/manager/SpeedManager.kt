package com.opentune.player.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession
import kotlinx.coroutines.CoroutineScope

internal val SPEED_VALUES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SPEED_LABELS = SPEED_VALUES.map { if (it == 1f) "1×" else "${it}×" }

internal class SpeedManager(
    private val scope: CoroutineScope,
    private val session: PlaybackSession,
    private val exo: ExoPlayer,
) {
    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable { stringResource(R.string.player_settings_speed) },
        children = ::buildSpeedChildren,
        isSelected = { false },
        onSelect = {},
    )

    private fun buildSpeedChildren(): List<PlayerMenuEntry> =
        SPEED_VALUES.mapIndexed { index, speed ->
            PlayerMenuEntry(
                label = @Composable { SPEED_LABELS[index] },
                children = { emptyList() },
                isSelected = { exo.playbackParameters.speed == speed },
                onSelect = {
                    exo.playbackParameters = PlaybackParameters(speed)
                },
            )
        }
}

@Composable
internal fun rememberSpeedManager(
    exo: ExoPlayer,
    session: PlaybackSession,
): SpeedManager {
    val scope = rememberCoroutineScope()

    DisposableEffect(exo, session) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                session.updateSpeed(parameters.speed)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    return remember {
        SpeedManager(
            scope = scope,
            session = session,
            exo = exo,
        )
    }
}
