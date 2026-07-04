package com.insomnia.content.ui.catalog

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.insomnia.content.contract.EntryInfo

/** Scroll only when [index] is outside the visible viewport — avoids snapping on every focus change. */
suspend fun LazyListState.scrollToIndexIfNeeded(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(index)
        return
    }
    val first = visible.first().index
    val last = visible.last().index
    if (index < first || index > last) {
        animateScrollToItem(index)
    }
}

suspend fun LazyGridState.scrollToIndexIfNeeded(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(index)
        return
    }
    val first = visible.first().index
    val last = visible.last().index
    if (index < first || index > last) {
        animateScrollToItem(index)
    }
}

@Composable
fun LaunchedScrollToIndexIfNeeded(
    listState: LazyListState,
    index: Int?,
    focusRequester: FocusRequester? = null,
) {
    LaunchedEffect(index, focusRequester) {
        val idx = index ?: return@LaunchedEffect
        listState.scrollToIndexIfNeeded(idx)
        focusRequester?.requestFocus()
    }
}

@Composable
fun rememberItemFocusRequesters(itemCount: Int): List<FocusRequester> =
    remember(itemCount) { List(itemCount) { FocusRequester() } }

fun restoreIndex(items: List<EntryInfo>, restoreRef: String?): Int =
    if (restoreRef != null) items.indexOfFirst { it.ref == restoreRef } else -1

/** One-shot focus restore when re-entering a list; [restoreRef] must be snapshotted at route entry. */
@Composable
fun LaunchedRestoreFocus(
    targetIndex: Int,
    restoreRef: String?,
    requesters: List<FocusRequester>,
) {
    var restored by remember(restoreRef) { mutableStateOf(false) }
    LaunchedEffect(targetIndex, restoreRef) {
        if (restored || targetIndex < 0) return@LaunchedEffect
        requesters.getOrNull(targetIndex)?.requestFocus()
        restored = true
    }
}

@Composable
fun rememberGridFocusRequesters(
    items: List<EntryInfo>,
    initialFocusRef: String?,
    gridState: LazyGridState,
): List<FocusRequester> {
    val targetIndex = remember(items.size, initialFocusRef) {
        val restored = restoreIndex(items, initialFocusRef)
        // When no restore ref, focus the first item instead of skipping
        if (restored < 0 && items.isNotEmpty()) 0 else restored
    }
    val requesters = rememberItemFocusRequesters(items.size)

    LaunchedEffect(targetIndex) {
        if (targetIndex >= 0) gridState.scrollToIndexIfNeeded(targetIndex)
    }
    LaunchedRestoreFocus(targetIndex, initialFocusRef, requesters)

    return requesters
}
