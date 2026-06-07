package com.opentune.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
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
fun PlaybackControllerBar(
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
        // Two rounded pill bars with a gap between them.
        // Bar 1 (blue, played): 0 → playedX - halfGap. Hidden when nothing played.
        // Bar 2 (dark gray base + gray buffered overlay): playedX + halfGap → end.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .height(4.dp),
        ) {
            val barH = size.height
            val r = CornerRadius(barH / 2)
            val gapPx = 4.dp.toPx()
            val halfGap = gapPx / 2f
            val totalW = size.width
            val playedX = totalW * playedFraction
            val bufferedX = totalW * bufferedFraction

            // Bar 2: dark gray base from (playedX + halfGap) to end
            val bar2Left = if (playedFraction > 0f) (playedX + halfGap).coerceAtMost(totalW) else 0f
            val bar2Width = (totalW - bar2Left).coerceAtLeast(0f)
            if (bar2Width > 0f) {
                val bar2Path = Path().apply {
                    addRoundRect(RoundRect(bar2Left, 0f, totalW, barH, r))
                }
                // dark gray base
                drawPath(bar2Path, Color(0xFF404040))
                // gray buffered overlay, clipped to bar 2 shape
                val bufferedInBar2 = (bufferedX - bar2Left).coerceIn(0f, bar2Width)
                if (bufferedInBar2 > 0f) {
                    clipPath(bar2Path) {
                        drawRect(
                            color = Color(0xFF808080),
                            topLeft = Offset(bar2Left, 0f),
                            size = Size(bufferedInBar2, barH),
                        )
                    }
                }
            }

            // Bar 1: blue played, 0 → (playedX - halfGap). Hidden when playedFraction == 0.
            val bar1Width = (playedX - halfGap).coerceAtLeast(0f)
            if (playedFraction > 0f && bar1Width > 0f) {
                drawRoundRect(
                    color = Color(0xFF2979FF),
                    topLeft = Offset(0f, 0f),
                    size = Size(bar1Width, barH),
                    cornerRadius = r,
                )
            }
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
