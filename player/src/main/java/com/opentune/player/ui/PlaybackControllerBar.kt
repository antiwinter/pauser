package com.opentune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Shared bottom controller bar. Display-only — position polling and visibility toggling
 * are handled by the caller (TvPlayer or PadPlayer).
 *
 * No touch scrubbing in Phase 2. Both progress layers are read-only indicators.
 */
@Composable
internal fun PlaybackControllerBar(
    position: Long,
    buffered: Long,
    duration: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = max(duration, 1L)
    val playedFraction = (position.toFloat() / safeDuration).coerceIn(0f, 1f)
    val bufferedFraction = (buffered.toFloat() / safeDuration).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Stacked progress bars: buffered (dim) under played (accent)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
        ) {
            LinearProgressIndicator(
                progress = { bufferedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF808080),
                trackColor = Color(0xFF404040),
            )
            LinearProgressIndicator(
                progress = { playedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF2979FF),
                trackColor = Color.Transparent,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) {
                            androidx.media3.ui.R.drawable.exo_styled_controls_pause
                        } else {
                            androidx.media3.ui.R.drawable.exo_styled_controls_play
                        },
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                text = "${formatMs(position)} / ${formatMs(duration)}",
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}
