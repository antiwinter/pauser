package com.insomnia.content.ui.catalog.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.FilenameDetector
import com.insomnia.content.ui.catalog.components.MediaEntryComponent
import com.insomnia.content.ui.catalog.rememberGridFocusRequesters
import com.insomnia.storage.TitleLang

@Composable
fun SearchScreen(
    results: SnapshotStateList<EntryInfo>,
    query: String,
    searching: Boolean,
    titleLang: TitleLang,
    imageLoader: ImageLoader,
    initialFocusRef: String? = null,
    onQueryChange: (String) -> Unit,
    onItemFocused: (EntryInfo) -> Unit = {},
    onOpenBrowse: (EntryInfo) -> Unit,
    onOpenDetail: (EntryInfo) -> Unit,
    onOpenPlayer: (EntryInfo) -> Unit,
    onOpenImageViewer: (String) -> Unit = {},
    onOpenAudioUnsupported: (String) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val focusRequesters = rememberGridFocusRequesters(results, initialFocusRef, gridState)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { M3Text("Search") },
            singleLine = true,
        )
        if (searching) {
            M3Text("Searching…")
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(results, key = { _, item -> item.ref }) { index, item ->
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
                            "Folder", "Season" -> { -> onOpenBrowse(item) }
                            "Movie", "Digipak", "Series" -> { -> onOpenDetail(item) }
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
        }
    }
}
