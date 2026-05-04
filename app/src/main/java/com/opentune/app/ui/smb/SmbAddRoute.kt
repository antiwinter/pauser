package com.opentune.app.ui.smb

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.drafts.SmbAddDraft
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.SmbSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OpenTuneSmbAdd"

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun SmbAddRoute(
    database: OpenTuneDatabase,
    onDone: () -> Unit,
) {
    val drafts = (LocalContext.current.applicationContext as OpenTuneApplication).addServerDraftStore

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) { drafts.loadSmb() }
        if (initial != null) {
            name = initial.displayName
            host = initial.host
            share = initial.shareName
            username = initial.username
            password = initial.password
            domain = initial.domain
        }
        snapshotFlow {
            SmbAddDraft(
                displayName = name,
                host = host,
                shareName = share,
                username = username,
                password = password,
                domain = domain,
            )
        }
            .distinctUntilChanged()
            .debounce(600)
            .collect { d ->
                withContext(Dispatchers.IO) { drafts.saveSmb(d) }
            }
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add SMB share")
            Text("Save share and Cancel are fixed below the form.")
            Text("Fields are saved automatically if you leave before saving.")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") })
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / IP") })
            OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Share name") })
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
            OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Domain (optional)") })
            error?.let { Text("Error: $it") }
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            database.smbSourceDao().insert(
                                SmbSourceEntity(
                                    displayName = name.ifBlank { share },
                                    host = host.trim(),
                                    shareName = share.trim(),
                                    username = username,
                                    password = password,
                                    domain = domain.ifBlank { null },
                                ),
                            )
                            drafts.clearSmb()
                        }
                        onDone()
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "insert smb source failed", e)
                        error = e.message
                    }
                }
            },
        ) {
            Text("Save share")
        }
        Button(onClick = onDone) { Text("Cancel") }
    }
}
