package com.opentune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.player.PlaybackDisplayInfo

internal class InfoOverlayState(
    val displayInfo: PlaybackDisplayInfo,
    val videoMime: String?,
    val videoDecoderStatus: String?,
    val audioMime: String?,
    val audioDecoderStatus: String?,
    val isHdrCapable: Boolean,
    val isHdrEnabled: Boolean,
    private val showState: MutableState<Boolean>,
    val mbpsState: MutableFloatState,
) {
    val isVisible: Boolean get() = showState.value

    fun show() { showState.value = true }
    fun hide() { showState.value = false }
}

@Composable
internal fun InfoOverlay(state: InfoOverlayState) {
    if (!state.isVisible) return
    val mbps = state.mbpsState.floatValue
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = state.displayInfo.title, color = Color.White, fontSize = 14.sp)
                Text(
                    text = trackLabel(state.videoMime, state.videoDecoderStatus),
                    color = if (isTrackFailed(state.videoMime, state.videoDecoderStatus)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
                Text(
                    text = trackLabel(state.audioMime, state.audioDecoderStatus),
                    color = if (isTrackFailed(state.audioMime, state.audioDecoderStatus)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
                if (state.isHdrCapable) {
                    Text(
                        text = "HDR",
                        color = if (state.isHdrEnabled) Color(0xFF4CAF50) else Color.White,
                        fontSize = 14.sp,
                    )
                }
                state.displayInfo.bitrate?.takeIf { it > 0 }?.let { br ->
                    Text(text = "%.1f Mbps".format(br / 1_000_000f), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }
            // Download speed on the right; hidden only before first measurement (-1 sentinel).
            if (mbps >= 0f) {
                Text(
                    text = "%.1f Mbps".format(mbps),
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Returns format, format[c2], format[n/a], or format[err]. */
private fun trackLabel(mime: String?, decoderStatus: String?): String {
    if (mime == null) return ""
    val codec = mime.replace(Regex("^(?:video|audio)/"), "")
    return when {
        decoderStatus == "n/a" -> "$codec[n/a]"
        decoderStatus == "err" -> "$codec[err]"
        !decoderStatus.isNullOrEmpty() -> "$codec[$decoderStatus]"
        else -> codec
    }
}

/** Returns true if the track has a decode error. */
private fun isTrackFailed(mime: String?, decoderStatus: String?): Boolean {
    return mime != null && decoderStatus == "err"
}

@Composable
internal fun rememberInfoOverlayState(
    instanceKey: String,
    displayInfo: PlaybackDisplayInfo,
    videoMime: String?,
    videoDecoderStatus: String?,
    audioMime: String?,
    audioDecoderStatus: String?,
    mbpsState: MutableFloatState,
    isHdrCapable: Boolean = false,
    isHdrEnabled: Boolean = false,
): InfoOverlayState {
    val showState = remember(instanceKey) { mutableStateOf(false) }
    return remember(instanceKey, displayInfo, videoMime, videoDecoderStatus, audioMime, audioDecoderStatus, isHdrCapable, isHdrEnabled) {
        InfoOverlayState(
            displayInfo = displayInfo,
            videoMime = videoMime,
            videoDecoderStatus = videoDecoderStatus,
            audioMime = audioMime,
            audioDecoderStatus = audioDecoderStatus,
            isHdrCapable = isHdrCapable,
            isHdrEnabled = isHdrEnabled,
            showState = showState,
            mbpsState = mbpsState,
        )
    }
}
