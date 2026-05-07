package com.opentune.player.speed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlayerStores
import com.opentune.player.R
import com.opentune.player.menu.PlayerMenuEntry
import com.opentune.storage.MediaStateKey
import com.opentune.storage.upsertSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal val SPEED_VALUES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SPEED_LABELS = SPEED_VALUES.map { if (it == 1f) "1×" else "${it}×" }

class SpeedController(
    private val scope: CoroutineScope,
    private val stores: PlayerStores,
    private val mediaStateKey: MediaStateKey,
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
                // Reading exo.playbackParameters.speed is accurate; the menu only renders
                // after it opens (by which time the saved speed is already applied to exo).
                isSelected = { exo.playbackParameters.speed == speed },
                onSelect = {
                    exo.playbackParameters = PlaybackParameters(speed)
                    scope.launch(Dispatchers.IO) {
                        stores.mediaStateStore.upsertSpeed(mediaStateKey, speed)
                    }
                },
            )
        }
}

@Composable
fun rememberSpeedController(
    exo: ExoPlayer,
    stores: PlayerStores,
    mediaStateKey: MediaStateKey,
): SpeedController {
    val scope = rememberCoroutineScope()
    return remember {
        SpeedController(
            scope = scope,
            stores = stores,
            mediaStateKey = mediaStateKey,
            exo = exo,
        )
    }
}
