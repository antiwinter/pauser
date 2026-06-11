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
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlaybackSpec
import com.opentune.storage.EntryStateKey

internal class InfoOverlayState(
    private val spec: PlaybackSpec,
    val durationMs: Long,
    val videoMime: String?,
    val videoDecoderName: String?,
    val audioMime: String?,
    val audioDecoderName: String?,
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
                if (state.durationMs > 0) {
                    Text(text = formatDuration(state.durationMs), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
                Text(
                    text = trackLabel(state.videoMime, state.videoDecoderName),
                    color = if (isTrackFailed(state.videoMime, state.videoDecoderName)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
                Text(
                    text = trackLabel(state.audioMime, state.audioDecoderName),
                    color = if (isTrackFailed(state.audioMime, state.audioDecoderName)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
            }
            if (mbps > 0f) {
                Text(
                    text = "%.1f Mbps".format(mbps),
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Returns codec name, "failed" if track exists but no decoder, or "" if no track. */
private fun trackLabel(mime: String?, decoderName: String?): String {
    if (mime == null) return ""
    val codec = mime.replace(Regex("^(?:video|audio)/"), "")
    // "passthrough" is a placeholder for offloaded/passthrough audio (no Android decoder)
    return if (decoderName == null || decoderName == "passthrough") {
        if (decoderName == "passthrough") codec else "$codec (failed)"
    } else codec
}

/** True when MIME is known but decoder never initialized (excludes passthrough). */
private fun isTrackFailed(mime: String?, decoderName: String?): Boolean {
    return mime != null && decoderName == null
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

@Composable
internal fun rememberInfoOverlayState(
    instanceKey: EntryStateKey,
    spec: PlaybackSpec,
    exo: ExoPlayer,
    videoMime: String?,
    videoDecoderName: String?,
    audioMime: String?,
    audioDecoderName: String?,
    mbpsState: MutableFloatState,
): InfoOverlayState {
    val showState = remember(instanceKey) { mutableStateOf(false) }

    return remember(instanceKey, spec, exo, videoMime, videoDecoderName, audioMime, audioDecoderName) {
        InfoOverlayState(
            spec = spec,
            durationMs = exo.duration.coerceAtLeast(0L),
            videoMime = videoMime,
            videoDecoderName = videoDecoderName,
            audioMime = audioMime,
            audioDecoderName = audioDecoderName,
            showState = showState,
            mbpsState = mbpsState,
        )
    }
}
