package com.opentune.content.ui.catalog

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.opentune.content.contract.EntryInfo

@Composable
fun rememberGridFocusRequesters(
    items: List<EntryInfo>,
    initialFocusRef: String?,
    gridState: LazyGridState,
): List<FocusRequester> {
    val targetIndex = remember(items.size, initialFocusRef) {
        if (initialFocusRef != null) items.indexOfFirst { it.ref == initialFocusRef } else -1
    }
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var restored by remember(initialFocusRef) { mutableStateOf(false) }

    LaunchedEffect(targetIndex) {
        if (targetIndex < 0) return@LaunchedEffect
        val visible = gridState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            gridState.scrollToItem(targetIndex)
            return@LaunchedEffect
        }
        val first = visible.first().index
        val last = visible.last().index
        if (targetIndex < first || targetIndex > last) {
            gridState.animateScrollToItem(targetIndex)
        }
    }
    LaunchedEffect(targetIndex, initialFocusRef) {
        if (restored || initialFocusRef == null || targetIndex < 0) return@LaunchedEffect
        requesters.getOrNull(targetIndex)?.requestFocus()
        restored = true
    }

    return requesters
}
