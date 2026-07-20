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
    results: List<QueryState>,
    loading: Boolean,
    error: String?,
    titleLang: TitleLang,
    initialFocusRef: String? = null,
    onLoadMore: () -> Unit,
    onSearch: (term: String, scope: SearchScope) -> Unit,
    onItemFocused: (queryIndex: Int, itemIndex: Int) -> Unit = { _, _ -> },
    onNavigateToPath: (QuerySpec) -> Unit,
    onOpenBrowseLocation: (endpointId: String, EntryInfo) -> Unit,
    onOpenDetail: (endpointId: String, EntryInfo) -> Unit,
    onOpenLivePlayer: (endpointId: String, EntryInfo) -> Unit = { _, _ -> },
    onOpenPlayer: (endpointId: String, EntryInfo) -> Unit,
    onOpenImageViewer: (endpointId: String, String) -> Unit = { _, _ -> },
    onOpenAudioUnsupported: (endpointId: String, String) -> Unit = { _, _ -> },
) {
    val allItems = results.flatMap { it.items }
    val gridState = rememberLazyGridState()

    val focusRequesters = rememberGridFocusRequesters(allItems, initialFocusRef, gridState)

    val nearEnd by remember {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - (COLUMNS * OVERSCAN_ROWS) - 1
        }
    }

    // Pagination: only for single-query mode
    LaunchedEffect(nearEnd) {
        if (nearEnd && !loading && results.size == 1) {
            val result = results[0]
            if (result.items.size < result.totalCount) {
                onLoadMore()
            }
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
        error?.let { Text("Error: $it") }
        if (error == null) {
            when {
                loading && allItems.isEmpty() -> Text("Loading…", modifier = Modifier.padding(bottom = 8.dp))
                !loading && allItems.isEmpty() -> Text("Nothing here.", modifier = Modifier.padding(bottom = 8.dp))
            }
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
            var globalIndex = 0
            results.forEachIndexed { queryIndex, result ->
                // Path header
                item(span = { GridItemSpan(COLUMNS) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pathLabel = result.spec.options.searchTerm?.let { term ->
                            "Search: \"$term\""
                        } ?: result.spec.location?.takeIf { it.isNotEmpty() }?.let { loc ->
                            loc.substringAfterLast('/')
                        } ?: "Root"
                        
                        Button(onClick = { 
                            onNavigateToPath(result.spec)
                        }) {
                            Text(pathLabel)
                        }
                        Text(
                            text = if (result.items.size < result.totalCount) {
                                "Showing ${result.items.size} of ${result.totalCount}"
                            } else {
                                "${result.items.size} items"
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                // Items
                result.items.forEachIndexed { itemIndex, item ->
                    val currentGlobalIndex = globalIndex++
                    item(key = item.ref) {
                        MediaEntryComponent(
                            item = item,
                            titleLang = titleLang,
                            imageLoader = result.client.imageLoader!!,
                            modifier = if (currentGlobalIndex < focusRequesters.size)
                                Modifier.focusRequester(focusRequesters[currentGlobalIndex])
                            else Modifier,
                            onFocused = { onItemFocused(queryIndex, itemIndex) },
                            onClick = {
                                fun resolveAction(type: String): (() -> Unit)? = when (type) {
                                    "Folder", "Season" -> { -> onOpenBrowseLocation(result.spec.endpointId, item) }
                                    "Movie", "Digipak", "Series" -> { -> onOpenDetail(result.spec.endpointId, item) }
                                    "Livepak" -> { -> onOpenLivePlayer(result.spec.endpointId, item) }
                                    "Episode", "Video", "LiveChannel" -> { -> onOpenPlayer(result.spec.endpointId, item) }
                                    "Image" -> { -> onOpenImageViewer(result.spec.endpointId, item.ref) }
                                    "Audio" -> { -> onOpenAudioUnsupported(result.spec.endpointId, item.ref) }
                                    else -> null
                                }
                                (resolveAction(item.type)
                                    ?: resolveAction(FilenameDetector.detectType(item.filename ?: item.title)))
                                    ?.invoke()
                            },
                        )
                    }
                }
            }
            if (loading && results.size == 1 && results[0].items.size < results[0].totalCount) {
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
