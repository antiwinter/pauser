package com.opentune.app.ui.emby

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
import com.opentune.app.drafts.EmbyAddDraft
import com.opentune.emby.api.EmbyApi
import com.opentune.emby.api.EmbyClientFactory
import com.opentune.emby.api.formatHttpExceptionForDisplay
import com.opentune.emby.api.runEmbyHttpPhase
import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.storage.EmbyServerEntity
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

private const val LOG_TAG = "OpenTuneEmbyAdd"

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun EmbyAddRoute(
    database: OpenTuneDatabase,
    deviceProfile: DeviceProfile,
    onDone: () -> Unit,
) {
    val drafts = (LocalContext.current.applicationContext as OpenTuneApplication).addServerDraftStore

    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) { drafts.loadEmby() }
        if (initial != null) {
            baseUrl = initial.baseUrl
            username = initial.username
            password = initial.password
        }
        snapshotFlow { Triple(baseUrl, username, password) }
            .distinctUntilChanged()
            .debounce(600)
            .collect { (u, user, pass) ->
                withContext(Dispatchers.IO) {
                    drafts.saveEmby(EmbyAddDraft(baseUrl = u, username = user, password = pass))
                }
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
            Text("Add Emby server")
            Text("Save and connect and Cancel are fixed below the form.")
            Text("Fields are saved automatically if you leave before connecting.")
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
        }
        Button(
            onClick = {
                scope.launch {
                    error = null
                    try {
                        withContext(Dispatchers.IO) {
                            addEmbyServer(database, deviceProfile, baseUrl.trim(), username.trim(), password)
                            drafts.clearEmby()
                        }
                        onDone()
                    } catch (e: HttpException) {
                        val msg = formatHttpExceptionForDisplay(e)
                        Log.e(LOG_TAG, "addEmbyServer failed: $msg", e)
                        error = msg
                    } catch (e: SerializationException) {
                        Log.e(LOG_TAG, "addEmbyServer JSON error: ${e.message}", e)
                        error = e.message ?: e::class.java.simpleName
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "addEmbyServer failed: ${e::class.java.simpleName} ${e.message}", e)
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
    Log.e(
        LOG_TAG,
        "addEmbyServer start normalizedUrl=$normalized usernameLen=${username.length} passwordEmpty=${password.isEmpty()}",
    )
    val unauth: EmbyApi = EmbyClientFactory.create(normalized, accessToken = null)
    val auth = runEmbyHttpPhase("authenticateByName") {
        unauth.authenticateByName(AuthenticateByNameRequest(username, password))
    }
    val token = auth.accessToken ?: error("No access token from Emby")
    val userId = auth.user?.id ?: error("No user id from Emby")
    val api = EmbyClientFactory.create(normalized, token)
    val info = runEmbyHttpPhase("getSystemInfo") { api.getSystemInfo() }
    val display = info.serverName ?: normalized
    val entity = EmbyServerEntity(
        displayName = display,
        baseUrl = normalized,
        userId = userId,
        accessToken = token,
        serverId = info.id,
    )
    database.embyServerDao().insert(entity)
    Log.w(LOG_TAG, "addEmbyServer success displayName=$display serverId=${info.id}")
}
