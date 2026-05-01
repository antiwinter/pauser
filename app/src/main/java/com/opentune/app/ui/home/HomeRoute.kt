package com.opentune.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.storage.OpenTuneDatabase

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    database: OpenTuneDatabase,
    onAddEmby: () -> Unit,
    onOpenServer: (Long) -> Unit,
    onEditEmby: (Long) -> Unit,
    onAddSmb: () -> Unit,
    onOpenSmb: (Long, String) -> Unit,
    onEditSmb: (Long) -> Unit,
) {
    val servers by database.embyServerDao().observeAll()
        .collectAsState(initial = emptyList())
    val smb by database.smbSourceDao().observeAll()
        .collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "OpenTune")
        Button(onClick = onAddEmby) { Text("Add Emby server") }
        Button(onClick = onAddSmb) { Text("Add SMB share") }
        Text(text = "Emby servers (focus a server, then press Menu on the remote to edit)")
        servers.forEach { s ->
            Button(
                onClick = { onOpenServer(s.id) },
                modifier = Modifier.onTvMenuKeyDown { onEditEmby(s.id) },
            ) {
                Text(s.displayName)
            }
        }
        Text(text = "SMB sources (Menu on the remote to edit)")
        smb.forEach { s ->
            Button(
                onClick = { onOpenSmb(s.id, "") },
                modifier = Modifier.onTvMenuKeyDown { onEditSmb(s.id) },
            ) {
                Text(s.displayName)
            }
        }
    }
}
