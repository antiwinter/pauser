package com.opentune.player.controller

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MENU_VISIBLE_ITEMS = 6
private const val ITEM_HEIGHT_DP = 44

/**
 * A single node in the player menu tree.
 *
 * Leaf nodes have an empty [children] list. Calling [onSelect] on a leaf triggers the action and
 * closes the menu. [isSelected] drives a persistent "active" indicator (●) independent of the
 * DPAD cursor.
 */
data class PlayerMenuEntry(
    val label: @Composable () -> String,
    val children: () -> List<PlayerMenuEntry>,
    val isSelected: @Composable () -> Boolean = { false },
    val onSelect: () -> Unit = {},
)

/**
 * Retained state for the two-level player settings menu.
 *
 * Create via [rememberMenuOverlay]. All Compose state is held as [mutableIntStateOf]
 * so reads inside composables trigger recomposition automatically.
 */
class MenuOverlay(private val entries: List<PlayerMenuEntry>) {

    // 0 = closed, 1 = top-level, 2 = sub-menu
    private var depth by mutableIntStateOf(0)
    private var topIndex by mutableIntStateOf(0)
    private var subIndex by mutableIntStateOf(0)

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
                    close()
                } else {
                    subIndex = 0
                    depth = 2
                }
            }
            2 -> {
                val children = entries.getOrNull(topIndex)?.children() ?: return
                children.getOrNull(subIndex)?.onSelect()
                close()
            }
        }
    }

    fun back() {
        when (depth) {
            2 -> depth = 1
            1 -> close()
        }
    }

    @Composable
    fun Overlay() {
        if (depth == 0) return
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .background(Color(0xFF1C1C1C), shape = RoundedCornerShape(12.dp))
                    .padding(vertical = 8.dp),
            ) {
                when (depth) {
                    1 -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(topIndex) {
                            val first = listState.firstVisibleItemIndex
                            when {
                                topIndex < first ->
                                    listState.scrollToItem(topIndex)
                                topIndex >= first + MENU_VISIBLE_ITEMS ->
                                    listState.scrollToItem(topIndex - MENU_VISIBLE_ITEMS + 1)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = (MENU_VISIBLE_ITEMS * ITEM_HEIGHT_DP).dp),
                        ) {
                            itemsIndexed(entries) { i, entry ->
                                PlayerMenuItem(
                                    label = entry.label(),
                                    isCursor = i == topIndex,
                                    isActive = entry.isSelected(),
                                )
                            }
                        }
                    }
                    2 -> {
                        val children = entries.getOrNull(topIndex)?.children() ?: emptyList()
                        val listState = rememberLazyListState()
                        LaunchedEffect(subIndex) {
                            val first = listState.firstVisibleItemIndex
                            when {
                                subIndex < first ->
                                    listState.scrollToItem(subIndex)
                                subIndex >= first + MENU_VISIBLE_ITEMS ->
                                    listState.scrollToItem(subIndex - MENU_VISIBLE_ITEMS + 1)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = (MENU_VISIBLE_ITEMS * ITEM_HEIGHT_DP).dp),
                        ) {
                            itemsIndexed(children) { i, entry ->
                                PlayerMenuItem(
                                    label = entry.label(),
                                    isCursor = i == subIndex,
                                    isActive = entry.isSelected(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberMenuOverlay(vararg entries: PlayerMenuEntry): MenuOverlay =
    remember(entries.toList()) { MenuOverlay(entries.toList()) }

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
