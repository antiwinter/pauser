package com.insomnia.content.ui.catalog.browse

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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import com.insomnia.content.ui.catalog.rememberGridFocusRequesters
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.FilenameDetector
import com.insomnia.storage.TitleLang
import com.insomnia.content.ui.catalog.components.MediaEntryComponent

private const val COLUMNS = 5
private const val OVERSCAN_ROWS = 3

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    logTag: String,
    items: List<EntryInfo>,
    totalCount: Int,
    loading: Boolean,
    error: String?,
    imageLoader: ImageLoader,
    titleLang: TitleLang,
    subtitle: String,
    initialFocusRef: String? = null,
    onLoadMore: () -> Unit,
    onSearch: (term: String, scope: SearchScope) -> Unit,
    onItemFocused: (EntryInfo) -> Unit = {},
    onOpenBrowseLocation: (EntryInfo) -> Unit,
    onOpenDetail: (EntryInfo) -> Unit,
    onOpenLivePlayer: (EntryInfo) -> Unit = {},
    onOpenPlayer: (EntryInfo) -> Unit,
    onOpenImageViewer: (String) -> Unit = {},
    onOpenAudioUnsupported: (String) -> Unit = {},
) {
    val gridState = rememberLazyGridState()

    val focusRequesters = rememberGridFocusRequesters(items, initialFocusRef, gridState)

    val nearEnd by remember {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - (COLUMNS * OVERSCAN_ROWS) - 1
        }
    }

    LaunchedEffect(nearEnd, items.size, totalCount) {
        if (nearEnd && !loading && items.size < totalCount) {
            onLoadMore()
        }
    }

    var searchModalOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { searchModalOpen = true }) { Text("Search") }
        }
        Text(text = subtitle, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        error?.let { Text("Error: $it") }
        if (error == null) {
            Text(
                when {
                    loading && items.isEmpty() -> "Loading…"
                    !loading && items.isEmpty() -> "Nothing here."
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
            itemsIndexed(items, key = { _, item -> item.ref }) { index, item ->
                MediaEntryComponent(
                    item = item,
                    titleLang = titleLang,
                    imageLoader = imageLoader,
                    modifier = if (index < focusRequesters.size)
                        Modifier.focusRequester(focusRequesters[index])
                    else Modifier,
                    onFocused = { onItemFocused(item) },
                    onClick = {
                        fun resolveAction(type: String): (() -> Unit)? = when (type) {
                            "Folder", "Season" -> { -> onOpenBrowseLocation(item) }
                            "Movie", "Digipak", "Series" -> { -> onOpenDetail(item) }
                            "Livepak" -> { -> onOpenLivePlayer(item) }
                            "Episode", "Video", "LiveChannel" -> { -> onOpenPlayer(item) }
                            "Image" -> { -> onOpenImageViewer(item.ref) }
                            "Audio" -> { -> onOpenAudioUnsupported(item.ref) }
                            else -> null
                        }
                        (resolveAction(item.type)
                            ?: resolveAction(FilenameDetector.detectType(item.filename ?: item.title)))
                            ?.invoke()
                    },
                )
            }
            if (loading && items.size < totalCount) {
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

    if (searchModalOpen) {
        SearchModal(
            onDismiss = { searchModalOpen = false },
            onConfirm = { term, scope ->
                searchModalOpen = false
                onSearch(term, scope)
            },
        )
    }
}
