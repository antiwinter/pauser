package com.opentune.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MbpsOverlay(mbps: Float) {
    // bitrateEstimate returns -1 when unknown, which maps to a negative float — skip display
    if (mbps <= 0f) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text = "%.1f Mbps".format(mbps),
            modifier = Modifier.padding(12.dp),
            color = Color.White,
            fontSize = 13.sp,
        )
    }
}
