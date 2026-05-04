package com.opentune.app.ui.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.EmbyRepository
import com.opentune.app.OpenTuneApplication
import com.opentune.emby.api.dto.BaseItemDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 100

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmbyBrowseRoute(
    app: OpenTuneApplication,
    serverId: Long,
    parentId: String,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, parentId) {
        loading = true
        error = null
        items = emptyList()
        totalCount = 0
        try {
            val server = app.database.embyServerDao().getById(serverId)
                ?: error("Server not found")
            val repo = EmbyRepository(
                baseUrl = server.baseUrl,
                userId = server.userId,
                accessToken = server.accessToken,
                deviceProfile = app.deviceProfile,
            )
            val result = withContext(Dispatchers.IO) {
                repo.getItems(
                    parentId = parentId,
                    recursive = false,
                    startIndex = 0,
                    limit = PAGE_SIZE,
                )
            }
            items = result.items
            totalCount = result.totalRecordCount
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        error?.let { Text("Error: $it") }
        if (error == null) {
            Text(
                when {
                    loading && items.isEmpty() -> "Loading…"
                    !loading && items.isEmpty() -> "This folder is empty."
                    totalCount > 0 && items.size < totalCount ->
                        "Showing ${items.size} of $totalCount — use Load more for the next page"
                    totalCount > 0 -> "$totalCount items"
                    else -> "${items.size} items"
                },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = items,
                key = { it.id ?: "${it.name}_${it.type}" },
            ) { item ->
                val id = item.id ?: return@items
                val type = item.type ?: ""
                val label = item.name ?: id
                Button(
                    onClick = {
                        if (type in CONTAINER_TYPES) {
                            onOpenFolder(id)
                        } else {
                            onOpenDetail(id)
                        }
                    },
                ) {
                    Text("$label ($type)")
                }
            }
            if (!loading && items.isNotEmpty() && items.size < totalCount) {
                item {
                    Button(
                        onClick = {
                            if (loading) return@Button
                            scope.launch {
                                loading = true
                                val startIndex = items.size
                                try {
                                    val server = app.database.embyServerDao().getById(serverId)
                                        ?: throw IllegalStateException("Server not found")
                                    val repo = EmbyRepository(
                                        baseUrl = server.baseUrl,
                                        userId = server.userId,
                                        accessToken = server.accessToken,
                                        deviceProfile = app.deviceProfile,
                                    )
                                    val result = withContext(Dispatchers.IO) {
                                        repo.getItems(
                                            parentId = parentId,
                                            recursive = false,
                                            startIndex = startIndex,
                                            limit = PAGE_SIZE,
                                        )
                                    }
                                    items = items + result.items
                                } catch (e: Exception) {
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

private val CONTAINER_TYPES = setOf(
    "Folder",
    "Series",
    "Season",
    "BoxSet",
    "MusicAlbum",
    "MusicArtist",
    "Playlist",
    "CollectionFolder",
    "UserView",
)
