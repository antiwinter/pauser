package com.opentune.app.ui.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.EmbyRepository
import com.opentune.app.OpenTuneApplication
import com.opentune.emby.api.EmbyImageUrls
import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.storage.EmbyServerEntity
import com.opentune.storage.FavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmbyDetailRoute(
    app: OpenTuneApplication,
    serverId: Long,
    itemId: String,
    onBack: () -> Unit,
    onPlay: (startMs: Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var item by remember { mutableStateOf<BaseItemDto?>(null) }
    var server by remember { mutableStateOf<EmbyServerEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resumeMs by remember { mutableStateOf(0L) }
    var isFavorite by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, itemId) {
        try {
            val s = withContext(Dispatchers.IO) { app.database.embyServerDao().getById(serverId) }
                ?: error("Server not found")
            server = s
            val repo = EmbyRepository(
                baseUrl = s.baseUrl,
                userId = s.userId,
                accessToken = s.accessToken,
                deviceProfile = app.deviceProfile,
            )
            val loaded = withContext(Dispatchers.IO) { repo.getItem(itemId) }
            item = loaded
            val key = progressKey(serverId, itemId)
            val prog = withContext(Dispatchers.IO) { app.database.playbackProgressDao().get(key) }
            resumeMs = prog?.positionMs ?: 0L
            val fav = withContext(Dispatchers.IO) { app.database.favoriteDao().find(serverId, itemId) }
            isFavorite = fav != null
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        error?.let { Text("Error: $it") }
        val i = item
        val s = server
        if (i != null && s != null) {
            Text(i.name ?: itemId)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { onPlay(resumeMs) }) {
                    Text(if (resumeMs > 0) "Resume" else "Play")
                }
                Button(onClick = { onPlay(0L) }) { Text("From start") }
            }
            Button(
                onClick = {
                    scope.launch {
                        val title = i.name ?: itemId
                        withContext(Dispatchers.IO) {
                            if (isFavorite) {
                                app.database.favoriteDao().delete(serverId, itemId)
                            } else {
                                app.database.favoriteDao().insert(
                                    FavoriteEntity(
                                        serverId = serverId,
                                        itemId = itemId,
                                        title = title,
                                        type = i.type,
                                    ),
                                )
                            }
                        }
                        isFavorite = !isFavorite
                    }
                },
            ) {
                Text(if (isFavorite) "Remove favorite" else "Add favorite")
            }
            val poster = EmbyImageUrls.primaryPoster(
                baseUrl = s.baseUrl,
                item = i,
                accessToken = s.accessToken,
            )
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = i.name,
                    modifier = Modifier
                        .height(280.dp)
                        .padding(bottom = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            i.overview?.let { synopsis ->
                Text(
                    synopsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun progressKey(serverId: Long, itemId: String) = "${serverId}_$itemId"
