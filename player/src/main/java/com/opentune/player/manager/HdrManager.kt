package com.opentune.player.manager

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.opentune.player.engine.TrackInfo

@UnstableApi
internal class HdrManager(
    private val isHdrCapableState: State<Boolean>,
    val displaySupportsHdr: Boolean,
) {
    val isHdrCapable: Boolean get() = isHdrCapableState.value
    // True when content has HDR metadata AND the display is capable of rendering it.
    val isHdrEnabled: Boolean get() = isHdrCapable && displaySupportsHdr
}

@UnstableApi
@Composable
internal fun rememberHdrManager(
    trackInfo: State<TrackInfo>,
): HdrManager {
    val context = LocalContext.current
    val isHdrCapable = remember(trackInfo) { derivedStateOf { trackInfo.value.isHdrCapable } }
    val displaySupportsHdr = remember {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }
    return remember(isHdrCapable) {
        HdrManager(isHdrCapable, displaySupportsHdr)
    }
}
