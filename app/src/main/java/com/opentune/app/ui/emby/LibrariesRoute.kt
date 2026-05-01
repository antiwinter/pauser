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
fun LibrariesRoute(
    app: OpenTuneApplication,
    serverId: Long,
    onBack: () -> Unit,
    onOpenLibrary: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        try {
            val server = app.database.embyServerDao().getById(serverId)
                ?: error("Server not found")
            val repo = EmbyRepository(
                baseUrl = server.baseUrl,
                userId = server.userId,
                accessToken = server.accessToken,
                deviceProfile = app.deviceProfile,
            )
            val views = withContext(Dispatchers.IO) { repo.getViews() }
            items = views.items
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        error?.let { Text("Error: $it") }
        Text("Libraries")
        items.forEach { item ->
            val id = item.id ?: return@forEach
            Button(onClick = { onOpenLibrary(id) }) {
                Text(item.name ?: id)
            }
        }
    }
}
