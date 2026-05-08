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

internal class InfoOsdController(
    private val spec: PlaybackSpec,
    private val videoMime: String?,
    private val videoDisabled: Boolean,
    private val audioMime: String?,
    private val audioDisabled: Boolean,
    private val showState: MutableState<Boolean>,
    private val upCountState: MutableState<Int>,
) {
    val onDpadUp: () -> Unit = {
        if (showState.value) {
            showState.value = false
            upCountState.value = 0
        } else {
            val next = upCountState.value + 1
            if (next >= 3) {
                showState.value = true
                upCountState.value = 0
            } else {
                upCountState.value = next
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
                        text = if (videoDisabled) "$mime ⚠️" else mime,
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
                audioMime?.let { mime ->
                    Text(
                        text = if (audioDisabled) "$mime ⚠️" else mime,
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
internal fun rememberInfoOsdController(
    instanceKey: MediaStateKey,
    spec: PlaybackSpec,
    videoMime: String?,
    videoDisabled: Boolean,
    audioMime: String?,
    audioDisabled: Boolean,
): InfoOsdController {
    val showState = remember(instanceKey) { mutableStateOf(false) }
    val upCountState = remember(instanceKey) { mutableStateOf(0) }
    val upCount = upCountState.value

    LaunchedEffect(upCount) {
        if (upCount > 0) {
            delay(2_000L)
            upCountState.value = 0
        }
    }

    return remember(instanceKey, spec, videoMime, videoDisabled, audioMime, audioDisabled) {
        InfoOsdController(
            spec = spec,
            videoMime = videoMime,
            videoDisabled = videoDisabled,
            audioMime = audioMime,
            audioDisabled = audioDisabled,
            showState = showState,
            upCountState = upCountState,
        )
    }
}
