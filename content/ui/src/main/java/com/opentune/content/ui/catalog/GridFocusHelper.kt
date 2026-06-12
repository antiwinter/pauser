package com.opentune.content.ui.catalog

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.opentune.content.contract.EntryInfo

@Composable
fun rememberGridFocusRequesters(
    items: List<EntryInfo>,
    initialFocusId: String?,
    gridState: LazyGridState,
): List<FocusRequester> {
    val targetIndex = remember(items.size, initialFocusId) {
        if (initialFocusId != null) items.indexOfFirst { it.id == initialFocusId } else -1
    }
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }

    LaunchedEffect(items.size, initialFocusId, targetIndex) {
        if (targetIndex >= 0) gridState.scrollToItem(targetIndex)
    }
    LaunchedEffect(items.size, initialFocusId, targetIndex) {
        if (targetIndex >= 0) requesters.getOrNull(targetIndex)?.requestFocus()
    }

    return requesters
}
