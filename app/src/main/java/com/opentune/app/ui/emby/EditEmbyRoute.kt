package com.opentune.app.ui.emby

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.emby.api.EmbyApi
import com.opentune.emby.api.EmbyClientFactory
import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditEmbyRoute(
    database: OpenTuneDatabase,
    serverId: Long,
    onDone: () -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId) {
        val row = withContext(Dispatchers.IO) { database.embyServerDao().getById(serverId) }
        if (row != null) {
            displayName = row.displayName
            baseUrl = row.baseUrl
        }
        loaded = true
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
            Text("Edit Emby server")
            Text("Re-enter username and password to refresh the session, then save.")
            if (!loaded) {
                Text("Loading…")
            } else {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                )
            }
            error?.let { Text("Error: $it") }
        }
        Button(
            onClick = {
                scope.launch {
                    error = null
                    try {
                        withContext(Dispatchers.IO) {
                            updateEmbyServer(
                                database = database,
                                serverId = serverId,
                                displayNameInput = displayName.trim(),
                                baseUrlInput = baseUrl.trim(),
                                username = username.trim(),
                                password = password,
                            )
                        }
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: e::class.java.simpleName
                    }
                }
            },
            enabled = loaded && username.isNotBlank() && password.isNotBlank(),
        ) {
            Text("Save changes")
        }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

private suspend fun updateEmbyServer(
    database: OpenTuneDatabase,
    serverId: Long,
    displayNameInput: String,
    baseUrlInput: String,
    username: String,
    password: String,
) {
    val existing = database.embyServerDao().getById(serverId) ?: error("Server not found")
    val normalized = EmbyClientFactory.normalizeBaseUrl(baseUrlInput)
    val unauth: EmbyApi = EmbyClientFactory.create(normalized, accessToken = null)
    val auth = unauth.authenticateByName(AuthenticateByNameRequest(username, password))
    val token = auth.accessToken ?: error("No access token from Emby")
    val userId = auth.user?.id ?: error("No user id from Emby")
    val api = EmbyClientFactory.create(normalized, token)
    val info = api.getSystemInfo()
    val display = displayNameInput.ifBlank { info.serverName ?: normalized }
    val entity = existing.copy(
        displayName = display,
        baseUrl = normalized,
        userId = userId,
        accessToken = token,
        serverId = info.id,
    )
    database.embyServerDao().update(entity)
}
