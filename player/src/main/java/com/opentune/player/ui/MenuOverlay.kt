package com.opentune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.opentune.player.manager.PlayerMenuEntry

private const val MENU_VISIBLE_ITEMS = 6
private const val ITEM_HEIGHT_DP = 44
private const val MENU_WIDTH_DP = 240
private const val MENU_END_PADDING_DP = 32

/**
 * Retained state for the two-level player settings menu.
 *
 * Create via [rememberMenuOverlayState]. All Compose state is held as [mutableIntStateOf]
 * so reads inside composables trigger recomposition automatically.
 */
class MenuOverlayState(initialEntries: List<PlayerMenuEntry>) {

    internal var entries by mutableStateOf(initialEntries)

    // 0 = closed, 1 = top-level, 2 = sub-menu
    internal var depth by mutableIntStateOf(0)
    internal var topIndex by mutableIntStateOf(0)
    internal var subIndex by mutableIntStateOf(0)

    val isOpen: Boolean get() = depth > 0

    fun open() {
        topIndex = 0
        depth = 1
    }

    fun close() {
        depth = 0
    }

    fun navigateUp() {
        when (depth) {
            1 -> topIndex = (topIndex - 1 + entries.size) % entries.size
            2 -> {
                val children = entries.getOrNull(topIndex)?.children() ?: return
                subIndex = (subIndex - 1 + children.size) % children.size
            }
        }
    }

    fun navigateDown() {
        when (depth) {
            1 -> topIndex = (topIndex + 1) % entries.size
            2 -> {
                val children = entries.getOrNull(topIndex)?.children() ?: return
                subIndex = (subIndex + 1) % children.size
            }
        }
    }

    fun confirm() {
        when (depth) {
            1 -> {
                val entry = entries.getOrNull(topIndex) ?: return
                if (entry.children().isEmpty()) {
                    entry.onSelect()
                    if (!entry.keepMenuOpen) close()
                } else {
                    subIndex = 0
                    depth = 2
                }
            }
            2 -> {
                val children = entries.getOrNull(topIndex)?.children() ?: return
                val entry = children.getOrNull(subIndex) ?: return
                entry.onSelect()
                if (!entry.keepMenuOpen) close()
            }
        }
    }

    fun back() {
        when (depth) {
            2 -> depth = 1
            1 -> close()
        }
    }
}

@Composable
fun rememberMenuOverlayState(vararg entries: PlayerMenuEntry): MenuOverlayState {
    val state = remember { MenuOverlayState(entries.toList()) }
    val newEntries = entries.toList()
    if (state.entries != newEntries) {
        state.topIndex = state.topIndex.coerceAtMost((newEntries.size - 1).coerceAtLeast(0))
        state.entries = newEntries
    }
    return state
}

@Composable
fun MenuOverlay(state: MenuOverlayState) {
    if (state.depth == 0) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(end = MENU_END_PADDING_DP.dp)
                .width(MENU_WIDTH_DP.dp)
                .background(Color(0xFF1C1C1C), shape = RoundedCornerShape(12.dp))
                .padding(vertical = 8.dp),
        ) {
            when (state.depth) {
                1 -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.topIndex) {
                        val first = listState.firstVisibleItemIndex
                        when {
                            state.topIndex < first ->
                                listState.scrollToItem(state.topIndex)
                            state.topIndex >= first + MENU_VISIBLE_ITEMS ->
                                listState.scrollToItem(state.topIndex - MENU_VISIBLE_ITEMS + 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = (MENU_VISIBLE_ITEMS * ITEM_HEIGHT_DP).dp),
                    ) {
                        itemsIndexed(state.entries) { i, entry ->
                            PlayerMenuItem(
                                label = entry.label(),
                                isCursor = i == state.topIndex,
                                isActive = entry.isSelected(),
                            )
                        }
                    }
                }
                2 -> {
                    val children = state.entries.getOrNull(state.topIndex)?.children() ?: emptyList()
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.subIndex) {
                        val first = listState.firstVisibleItemIndex
                        when {
                            state.subIndex < first ->
                                listState.scrollToItem(state.subIndex)
                            state.subIndex >= first + MENU_VISIBLE_ITEMS ->
                                listState.scrollToItem(state.subIndex - MENU_VISIBLE_ITEMS + 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = (MENU_VISIBLE_ITEMS * ITEM_HEIGHT_DP).dp),
                    ) {
                        itemsIndexed(children) { i, entry ->
                            PlayerMenuItem(
                                label = entry.label(),
                                isCursor = i == state.subIndex,
                                isActive = entry.isSelected(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerMenuItem(label: String, isCursor: Boolean, isActive: Boolean) {
    val prefix = if (isActive) "● " else "  "
    Text(
        text = "$prefix$label",
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCursor) Color(0xFF3D3D3D) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (isCursor) Color.White else Color(0xFFCCCCCC),
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
