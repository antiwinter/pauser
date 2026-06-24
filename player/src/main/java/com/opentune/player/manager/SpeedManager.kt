package com.opentune.player.manager

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.PlaybackParameters
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession

internal val SPEED_VALUES = listOf(0.25f, 0.5f, 1f, 1.25f, 1.5f, 2f)
private val SPEED_LABELS = SPEED_VALUES.map { if (it == 1f) "1×" else "${it}×" }

internal class SpeedManager(
    private val session: PlaybackSession,
) : PlaybackManager {

    override fun onPrepare() {
        val speed = session.currentSpec?.state?.speed ?: 1f
        session.exo.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2.0f))
    }

    override val menuEntries: List<PlayerMenuEntry> = listOf(
        PlayerMenuEntry(
            label = @Composable { stringResource(R.string.player_settings_speed) },
            children = ::buildSpeedChildren,
            isSelected = { false },
            onSelect = {},
        )
    )

    // Setting exo.playbackParameters is enough — PlaybackSession observes onPlaybackParametersChanged
    // and persists the speed, so there is no per-manager listener here.
    private fun buildSpeedChildren(): List<PlayerMenuEntry> =
        SPEED_VALUES.mapIndexed { index, speed ->
            PlayerMenuEntry(
                label = @Composable { SPEED_LABELS[index] },
                children = { emptyList() },
                isSelected = { session.exo.playbackParameters.speed == speed },
                onSelect = {
                    session.exo.playbackParameters = PlaybackParameters(speed)
                },
            )
        }
}
