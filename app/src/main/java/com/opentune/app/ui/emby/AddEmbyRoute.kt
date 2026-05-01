package com.opentune.app.ui.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
import com.opentune.emby.api.EmbyClientFactory
import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.EmbyApi
import com.opentune.storage.EmbyServerEntity
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddEmbyRoute(
    database: OpenTuneDatabase,
    deviceProfile: DeviceProfile,
    onDone: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add Emby server")
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server URL (e.g. http://192.168.1.10:8096)") },
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
        error?.let { Text("Error: $it") }
        Button(
            onClick = {
                scope.launch {
                    error = null
                    try {
                        withContext(Dispatchers.IO) {
                            addEmbyServer(database, deviceProfile, baseUrl.trim(), username.trim(), password)
                        }
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: e::class.java.simpleName
                    }
                }
            },
        ) {
            Text("Save and connect")
        }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

private suspend fun addEmbyServer(
    database: OpenTuneDatabase,
    @Suppress("UNUSED_PARAMETER") deviceProfile: DeviceProfile,
    baseUrl: String,
    username: String,
    password: String,
) {
    val normalized = EmbyClientFactory.normalizeBaseUrl(baseUrl)
    val unauth: EmbyApi = EmbyClientFactory.create(normalized, accessToken = null)
    val auth = unauth.authenticateByName(AuthenticateByNameRequest(username, password))
    val token = auth.accessToken ?: error("No access token from Emby")
    val userId = auth.user?.id ?: error("No user id from Emby")
    val api = EmbyClientFactory.create(normalized, token)
    val info = api.getSystemInfo()
    val display = info.serverName ?: normalized
    val entity = EmbyServerEntity(
        displayName = display,
        baseUrl = normalized,
        userId = userId,
        accessToken = token,
        serverId = info.id,
    )
    database.embyServerDao().insert(entity)
}
