package com.opentune.app.ui.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    app: OpenTuneApplication,
    serverId: Long,
    parentId: String,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, parentId) {
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
                repo.getItems(parentId = parentId, recursive = false)
            }
            items = result.items
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        error?.let { Text("Error: $it") }
        items.forEach { item ->
            val id = item.id ?: return@forEach
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
