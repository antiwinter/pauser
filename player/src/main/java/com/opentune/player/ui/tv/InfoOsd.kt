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

internal class InfoOsd(
    private val spec: PlaybackSpec,
    private val durationMs: Long,
    private val videoMime: String?,
    private val videoDecoderName: String?,
    private val videoFailed: Boolean,
    private val audioMime: String?,
    private val audioDecoderName: String?,
    private val audioFailed: Boolean,
    private val showState: MutableState<Boolean>,
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
                        text = trackLabel(videoMime, videoDecoderName, videoFailed),
                        color = if (videoFailed) Color(0xFFFF6B6B) else Color.White,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = trackLabel(audioMime, audioDecoderName, audioFailed),
                        color = if (audioFailed) Color(0xFFFF6B6B) else Color.White,
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

/** Extracts decoder prefix (e.g., "c2", "OMX") from full decoder name. */
private fun simplifyDecoderName(decoderName: String): String {
    return when {
        decoderName.startsWith("c2.") -> "c2"
        decoderName.startsWith("OMX.") -> "OMX"
        else -> decoderName.substringBefore('.').takeIf { it.isNotEmpty() } ?: decoderName
    }
}

/** Returns format, format[decoder], or format[failed] based on state. */
private fun trackLabel(mime: String?, decoderName: String?, failed: Boolean): String {
    if (mime == null) return ""
    val codec = mime.replace(Regex("^(?:video|audio)/"), "")
    return when {
        failed -> "$codec[failed]"
        decoderName != null -> "$codec[${simplifyDecoderName(decoderName)}]"
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
    videoDecoderName: String?,
    videoFailed: Boolean,
    audioMime: String?,
    audioDecoderName: String?,
    audioFailed: Boolean,
    mbpsState: MutableFloatState,
): InfoOsd {
    val showState = remember(instanceKey) { mutableStateOf(false) }

    return remember(instanceKey, spec, exo, videoMime, videoDecoderName, videoFailed, audioMime, audioDecoderName, audioFailed) {
        InfoOsd(
            spec = spec,
            durationMs = exo.duration.coerceAtLeast(0L),
            videoMime = videoMime,
            videoDecoderName = videoDecoderName,
            videoFailed = videoFailed,
            audioMime = audioMime,
            audioDecoderName = audioDecoderName,
            audioFailed = audioFailed,
            showState = showState,
            mbpsState = mbpsState,
        )
    }
}
