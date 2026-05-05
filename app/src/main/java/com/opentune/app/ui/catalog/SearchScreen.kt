package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    logTag: String,
    results: SnapshotStateList<MediaListItem>,
    searchFn: suspend (String) -> List<MediaListItem>,
    onBack: () -> Unit,
    onOpenBrowse: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onItemsLoaded: ((List<MediaListItem>) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(280)
        val q = query.trim()
        if (q.isEmpty()) {
            results.clear()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            val fetched = withContext(Dispatchers.IO) { searchFn(q) }
            results.clear()
            results.addAll(fetched)
            onItemsLoaded?.invoke(fetched)
        } catch (e: Exception) {
            Log.e(logTag, "search", e)
            results.clear()
        } finally {
            searching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { M3Text("Search") },
            singleLine = true,
        )
        if (searching) {
            Text("Searching\u2026")
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
            items(results, key = { it.id }) { item ->
                MediaEntryComponent(
                    item = item,
                    onClick = {
                        when (item.kind) {
                            MediaEntryKind.Folder -> onOpenBrowse(item.id)
                            MediaEntryKind.Playable, MediaEntryKind.Other -> onOpenDetail(item.id)
                        }
                    },
                )
            }
        }
    }
}
