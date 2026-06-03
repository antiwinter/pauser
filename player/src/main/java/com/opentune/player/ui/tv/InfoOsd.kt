package com.opentune.player.ui.tv

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

internal class InfoOsd(
    private val spec: PlaybackSpec,
    private val durationMs: Long,
    private val videoMime: String?,
    private val videoDecoderStatus: String,
    private val audioMime: String?,
    private val audioDecoderStatus: String,
    private val showState: androidx.compose.runtime.MutableState<Boolean>,
    val mbpsState: MutableFloatState,
) {
    val isVisible: Boolean get() = showState.value

    fun show() { showState.value = true }
    fun hide() { showState.value = false }

    @Composable
    fun Osd() {
        if (!showState.value) return
        val mbps = mbpsState.floatValue
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
                    Text(text = spec.title, color = Color.White, fontSize = 14.sp)
                    if (durationMs > 0) {
                        Text(text = formatDuration(durationMs), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    }
                    Text(
                        text = trackLabel(videoMime, videoDecoderStatus),
                        color = if (videoDecoderStatus.isNotEmpty()) Color(0xFFFF6B6B) else Color.White,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = trackLabel(audioMime, audioDecoderStatus),
                        color = if (audioDecoderStatus.isNotEmpty()) Color(0xFFFF6B6B) else Color.White,
                        fontSize = 14.sp,
                    )
                    spec.bitrate?.takeIf { it > 0 }?.let { br ->
                        Text(text = "%.1f Mbps".format(br / 1_000_000f), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    }
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
}

/** Returns format, format[c2], format[n/a], or format[err]. */
private fun trackLabel(mime: String?, decoderStatus: String): String {
    if (mime == null) return ""
    val codec = mime.replace(Regex("^(?:video|audio)/"), "")
    return when {
        decoderStatus == "n/a" -> "$codec[n/a]"
        decoderStatus == "err" -> "$codec[err]"
        decoderStatus.isNotEmpty() -> "$codec[$decoderStatus]"
        else -> codec
    }
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
internal fun rememberInfoOsd(
    instanceKey: EntryStateKey,
    spec: PlaybackSpec,
    exo: ExoPlayer,
    videoMime: String?,
    videoDecoderStatus: String,
    audioMime: String?,
    audioDecoderStatus: String,
    mbpsState: MutableFloatState,
): InfoOsd {
    val showState = remember(instanceKey) { mutableStateOf(false) }

    return remember(instanceKey, spec, exo, videoMime, videoDecoderStatus, audioMime, audioDecoderStatus) {
        InfoOsd(
            spec = spec,
            durationMs = exo.duration.coerceAtLeast(0L),
            videoMime = videoMime,
            videoDecoderStatus = videoDecoderStatus,
            audioMime = audioMime,
            audioDecoderStatus = audioDecoderStatus,
            showState = showState,
            mbpsState = mbpsState,
        )
    }
}
