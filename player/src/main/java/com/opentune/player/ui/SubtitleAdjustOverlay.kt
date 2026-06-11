package com.opentune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.player.R

@Composable
fun SubtitleAdjustOverlay(
    isActive: Boolean,
    translationYPx: Float,
    sizeScale: Float,
) {
    if (!isActive) return
    val previewBottomDp = with(LocalDensity.current) { translationYPx.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.subtitle_adjust_sample),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (previewBottomDp + 48.dp).coerceAtLeast(48.dp))
                .graphicsLayer { scaleX = sizeScale; scaleY = sizeScale }
                .background(Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 28.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 20.sp,
        )
        Text(
            text = stringResource(R.string.subtitle_adjust_hint),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color(0xFFAAAAAA),
            fontSize = 13.sp,
        )
    }
}
