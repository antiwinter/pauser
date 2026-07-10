package com.insomnia.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insomnia.core.theme.ScrimStrong

private const val CHANNEL_COLUMNS = 4
private const val CHANNEL_VISIBLE_ROWS = 5

/**
 * Retained state for the live-player channel list overlay (opened via MENU).
 * Grid cursor navigation over the channel names; confirm jumps to a channel.
 */
class ChannelListOverlayState {
    internal var channels by mutableStateOf<List<String>>(emptyList())
    internal var current by mutableIntStateOf(0)
    internal var cursor by mutableIntStateOf(0)
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        cursor = current.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        isOpen = true
    }

    fun close() { isOpen = false }

    fun navigate(dRow: Int, dCol: Int) {
        if (channels.isEmpty()) return
        val target = when {
            dCol != 0 -> cursor + dCol
            else -> cursor + dRow * CHANNEL_COLUMNS
        }
        if (target in channels.indices) cursor = target
    }
}

@Composable
fun rememberChannelListOverlayState(
    channels: List<String>,
    current: Int,
): ChannelListOverlayState {
    val state = remember { ChannelListOverlayState() }
    state.channels = channels
    state.current = current
    return state
}

@Composable
fun ChannelListOverlay(
    state: ChannelListOverlayState,
    onSelect: (Int) -> Unit,
) {
    if (!state.isOpen) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        val gridState = rememberLazyGridState()
        LaunchedEffect(state.cursor) {
            gridState.animateScrollToItem((state.cursor - CHANNEL_COLUMNS).coerceAtLeast(0))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(CHANNEL_COLUMNS),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.channels) { i, name ->
                ChannelListItem(
                    name = name,
                    isCursor = i == state.cursor,
                    isCurrent = i == state.current,
                )
            }
        }
    }
}

@Composable
private fun ChannelListItem(name: String, isCursor: Boolean, isCurrent: Boolean) {
    Text(
        text = if (isCurrent) "● $name" else name,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isCursor -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontSize = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
