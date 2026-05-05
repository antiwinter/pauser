package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.provider.BrowsePageResult
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 100

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    logTag: String,
    items: SnapshotStateList<MediaListItem>,
    loadPage: suspend (startIndex: Int, limit: Int) -> BrowsePageResult,
    subtitle: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenBrowseLocation: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onItemsLoaded: ((List<MediaListItem>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var totalCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetAndLoad() {
        scope.launch {
            loading = true
            error = null
            items.clear()
            totalCount = 0
            try {
                val page = withContext(Dispatchers.IO) { loadPage(0, PAGE_SIZE) }
                items.addAll(page.items)
                totalCount = page.totalCount
                onItemsLoaded?.invoke(page.items)
            } catch (e: Exception) {
                Log.e(logTag, "browse load", e)
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(loadPage) {
        resetAndLoad()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onBack) { Text("Back") }
            Button(onClick = onSearch) { Text("Search") }
        }
        Text(text = subtitle, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        error?.let { Text("Error: $it") }
        if (error == null) {
            Text(
                when {
                    loading && items.isEmpty() -> "Loading\u2026"
                    !loading && items.isEmpty() -> "Nothing here."
                    totalCount > 0 && items.size < totalCount -> "Showing ${items.size} of $totalCount"
                    totalCount > 0 -> "$totalCount items"
                    else -> "${items.size} items"
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaEntryComponent(
                    item = item,
                    onClick = {
                        when (item.kind) {
                            MediaEntryKind.Folder -> onOpenBrowseLocation(item.id)
                            MediaEntryKind.Playable, MediaEntryKind.Other -> onOpenDetail(item.id)
                        }
                    },
                )
            }
            if (!loading && items.isNotEmpty() && items.size < totalCount) {
                item(span = { GridItemSpan(5) }) {
                    Button(
                        onClick = {
                            if (loading) return@Button
                            scope.launch {
                                loading = true
                                try {
                                    val page = withContext(Dispatchers.IO) {
                                        loadPage(items.size, PAGE_SIZE)
                                    }
                                    items.addAll(page.items)
                                    onItemsLoaded?.invoke(page.items)
                                } catch (e: Exception) {
                                    Log.e(logTag, "load more", e)
                                    error = e.message
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Text("Load more (${items.size} / $totalCount)")
                    }
                }
            }
        }
    }
}
