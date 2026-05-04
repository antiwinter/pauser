package com.opentune.app.ui.smb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.opentune.smb.SmbListEntry
import com.opentune.smb.SmbSession
import com.opentune.smb.SmbCredentials
import com.opentune.smb.listDirectory
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbBrowseRoute(
    database: OpenTuneDatabase,
    sourceId: Long,
    initialPath: String,
    onBack: () -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    var session by remember { mutableStateOf<SmbSession?>(null) }
    var path by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<SmbListEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceId) {
        try {
            val src = withContext(Dispatchers.IO) { database.smbSourceDao().getById(sourceId) }
                ?: error("SMB source not found")
            session = withContext(Dispatchers.IO) {
                SmbSession.open(
                    SmbCredentials(
                        host = src.host,
                        shareName = src.shareName,
                        username = src.username,
                        password = src.password,
                        domain = src.domain,
                    ),
                )
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    DisposableEffect(session) {
        val openSession = session
        onDispose {
            openSession?.close()
        }
    }

    LaunchedEffect(session, path) {
        val s = session ?: return@LaunchedEffect
        try {
            entries = withContext(Dispatchers.IO) {
                s.share.listDirectory(path)
            }
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
        Text("Path: ${path.ifEmpty { "/" }}")
        if (path.isNotEmpty()) {
            Button(onClick = {
                val parent = path.trimEnd('/').substringBeforeLast('/', "")
                path = parent
            }) {
                Text("Up")
            }
        }
        entries.forEach { e ->
            Button(
                onClick = {
                    if (e.isDirectory) {
                        path = e.path
                    } else if (isVideoFile(e.name)) {
                        onPlayVideo(e.path)
                    }
                },
            ) {
                Text("${e.name}${if (e.isDirectory) "/" else ""}")
            }
        }
    }
}

private fun isVideoFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".mkv") || lower.endsWith(".mp4") || lower.endsWith(".avi") ||
        lower.endsWith(".webm") || lower.endsWith(".m4v")
}
