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
    initialFocusRef: String?,
    gridState: LazyGridState,
): List<FocusRequester> {
    val targetIndex = remember(items.size, initialFocusRef) {
        if (initialFocusRef != null) items.indexOfFirst { it.ref == initialFocusRef } else -1
    }
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }

    LaunchedEffect(items.size, initialFocusRef, targetIndex) {
        if (targetIndex >= 0) gridState.scrollToItem(targetIndex)
    }
    LaunchedEffect(items.size, initialFocusRef, targetIndex) {
        if (targetIndex >= 0) requesters.getOrNull(targetIndex)?.requestFocus()
    }

    return requesters
}
