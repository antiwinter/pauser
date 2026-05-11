package com.opentune.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.MediaStateKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class InfoOsd(
    private val spec: PlaybackSpec,
    private val videoMime: String?,
    private val videoDecoderName: String?,
    private val audioMime: String?,
    private val audioDecoderName: String?,
    private val showState: MutableState<Boolean>,
    private val countState: MutableState<Int>,
) {
    val isVisible: Boolean get() = showState.value

    fun toggle() {
        if (showState.value) {
            showState.value = false
        } else {
            if (countState.value >= 3) {
                showState.value = true
                countState.value = 0
            } else {
                countState.value++
            }
        }
    }

    @Composable
    fun Osd() {
        if (!showState.value) return
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = spec.displayTitle, color = Color.White, fontSize = 14.sp)
                Text(text = formatDuration(spec.durationMs), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                videoMime?.let { mime ->
                    Text(
                        text = if (videoDecoderName != null) "$mime $videoDecoderName" else mime,
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
                audioMime?.let { mime ->
                    Text(
                        text = if (audioDecoderName != null) "$mime $audioDecoderName" else mime,
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "?"
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
    instanceKey: MediaStateKey,
    spec: PlaybackSpec,
    videoMime: String?,
    videoDecoderName: String?,
    audioMime: String?,
    audioDecoderName: String?,
): InfoOsd {
    val showState = remember(instanceKey) { mutableStateOf(false) }
    val countState = remember(instanceKey) { mutableStateOf(0) }

    // 1s inactivity timeout: reset count if no toggle occurs within 1s
    LaunchedEffect(countState.value) {
        val current = countState.value
        if (current > 0 && !showState.value) {
            delay(1_000L)
            // Only reset if count hasn't changed during the delay
            if (countState.value == current) {
                countState.value = 0
            }
        }
    }

    return remember(instanceKey, spec, videoMime, videoDecoderName, audioMime, audioDecoderName) {
        InfoOsd(
            spec = spec,
            videoMime = videoMime,
            videoDecoderName = videoDecoderName,
            audioMime = audioMime,
            audioDecoderName = audioDecoderName,
            showState = showState,
            countState = countState,
        )
    }
}
