package com.opentune.player.manager

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import com.opentune.player.R
import com.opentune.player.engine.TrackInfo

private const val HDR_LOG_TAG = "OT_Hdr"

@UnstableApi
internal class HdrManager(
    private val isHdrCapable: State<Boolean>,
    private val hdrEnabledState: MutableState<Boolean>,
) {
    val isHdrEnabled: Boolean get() = hdrEnabledState.value
    val shouldShow: Boolean get() = isHdrCapable.value

    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable {
            if (hdrEnabledState.value) stringResource(R.string.player_settings_disable_hdr)
            else stringResource(R.string.player_settings_enable_hdr)
        },
        children = { emptyList() },
        isSelected = { hdrEnabledState.value },
        onSelect = {
            val newState = !hdrEnabledState.value
            hdrEnabledState.value = newState
            Log.d(HDR_LOG_TAG, "HDR toggle → ${if (newState) "ON (SurfaceView)" else "OFF (TextureView)"}")
        },
    )
}

@UnstableApi
@Composable
internal fun rememberHdrManager(
    trackInfo: State<TrackInfo>,
): HdrManager {
    val hdrEnabledState = remember { mutableStateOf(false) }
    val isHdrCapable = remember { derivedStateOf { trackInfo.value.isHdrCapable } }

    // When content changes to non-HDR, reset HDR enabled state.
    if (!isHdrCapable.value && hdrEnabledState.value) {
        hdrEnabledState.value = false
        Log.d(HDR_LOG_TAG, "Content no longer HDR-capable — HDR disabled")
    }

    return remember(isHdrCapable, hdrEnabledState) {
        HdrManager(
            isHdrCapable = isHdrCapable,
            hdrEnabledState = hdrEnabledState,
        )
    }
}
