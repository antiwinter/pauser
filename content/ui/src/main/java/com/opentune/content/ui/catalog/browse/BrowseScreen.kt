package com.opentune.content.ui.catalog.browse

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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.FilenameDetector
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.components.MediaEntryComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 30
private const val COLUMNS = 5
private const val OVERSCAN_ROWS = 3

/**
 * Browse screen that displays items in a paginated grid.
 *
 * This component is ONLY responsible for:
 * - Displaying items provided by the caller
 * - Triggering loadMore when the user scrolls near the end
 *
 * Initial load is driven externally by the caller (ViewModel).
 * This ensures that when navigating back, the screen shows
 * cached items without clearing/reloading.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    logTag: String,
    items: SnapshotStateList<EntryInfo>,
    loadMore: suspend (startIndex: Int, limit: Int) -> EntryList,
    subtitle: String,
    titleLang: TitleLang,
    imageLoader: ImageLoader,
    totalCount: Int,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowseLocation: (EntryInfo) -> Unit,
    onOpenDetail: (EntryInfo) -> Unit,
    onOpenPlayer: (String, Long?) -> Unit,
    onOpenImageViewer: (String) -> Unit = {},
    onOpenAudioUnsupported: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var localLoading by remember { mutableStateOf(loading) }

    val nearEnd by remember {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - (COLUMNS * OVERSCAN_ROWS) - 1
        }
    }

    LaunchedEffect(nearEnd, items.size, totalCount) {
        val effectiveLoading = localLoading || loading
        if (nearEnd && !effectiveLoading && items.size < totalCount) {
            localLoading = true
            try {
                val page = withContext(Dispatchers.IO) { loadMore(items.size, PAGE_SIZE) }
                items.addAll(page.items)
            } catch (e: Exception) {
                Log.e(logTag, "load more", e)
            } finally {
                localLoading = false
            }
        }
    }

    val effectiveLoading = loading || localLoading

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
            Button(onClick = onOpenSettings) { Text("Settings") }
        }
        Text(text = subtitle, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        error?.let { Text("Error: $it") }
        if (error == null) {
            Text(
                when {
                    effectiveLoading && items.isEmpty() -> "Loading…"
                    !effectiveLoading && items.isEmpty() -> "Nothing here."
                    totalCount > 0 && items.size < totalCount -> "Showing ${items.size} of $totalCount"
                    totalCount > 0 -> "$totalCount items"
                    else -> "${items.size} items"
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
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
                    titleLang = titleLang,
                    imageLoader = imageLoader,
                    onClick = {
                        fun resolveAction(type: String): (() -> Unit)? = when (type) {
                            "Folder", "Season" -> { -> onOpenBrowseLocation(item) }
                            "Movie", "Digipak", "Series" -> { -> onOpenDetail(item) }
                            "Episode", "Video" -> { -> onOpenPlayer(item.id, item.userData?.positionMs) }
                            "Image" -> { -> onOpenImageViewer(item.id) }
                            "Audio" -> { -> onOpenAudioUnsupported(item.id) }
                            else -> null
                        }
                        (resolveAction(item.type)
                            ?: resolveAction(FilenameDetector.detectType(item.filename ?: item.title)))
                            ?.invoke()
                    },
                )
            }
            if (effectiveLoading && items.size < totalCount) {
                item(span = { GridItemSpan(COLUMNS) }) {
                    Text(
                        "Loading…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
