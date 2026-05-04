package com.opentune.emby.api

import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.runEmbyHttpPhase
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.ProviderConfigBackend
import com.opentune.provider.ServerStore
import com.opentune.provider.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbyConfigBackend(
    private val deviceProfile: DeviceProfile,
) : ProviderConfigBackend {

    override suspend fun submitAdd(values: Map<String, String>, serverStore: ServerStore): SubmitResult =
        withContext(Dispatchers.IO) {
            try {
                addHttpLibraryServer(
                    serverStore = serverStore,
                    deviceProfile = deviceProfile,
                    baseUrl = values["base_url"]?.trim().orEmpty(),
                    username = values["username"]?.trim().orEmpty(),
                    password = values["password"].orEmpty(),
                )
                SubmitResult.Success
            } catch (e: Exception) {
                SubmitResult.Error(e.message ?: "Add server failed")
            }
        }

    override suspend fun submitEdit(
        sourceId: Long,
        values: Map<String, String>,
        serverStore: ServerStore,
    ): SubmitResult = withContext(Dispatchers.IO) {
        try {
            updateHttpLibraryServer(
                serverStore = serverStore,
                serverId = sourceId,
                displayNameInput = values["display_name"]?.trim().orEmpty(),
                baseUrlInput = values["base_url"]?.trim().orEmpty(),
                username = values["username"]?.trim().orEmpty(),
                password = values["password"].orEmpty(),
                deviceProfile = deviceProfile,
            )
            SubmitResult.Success
        } catch (e: Exception) {
            SubmitResult.Error(e.message ?: "Update server failed")
        }
    }

    override suspend fun loadEditFields(sourceId: Long, serverStore: ServerStore): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val row = serverStore.get(sourceId) ?: return@withContext null
            val fields = EmbyServerFieldsJson.parse(row.fieldsJson)
            mapOf(
                "display_name" to row.displayName,
                "base_url" to fields.baseUrl,
                "username" to "",
                "password" to "",
            )
        }
}

private suspend fun addHttpLibraryServer(
    serverStore: ServerStore,
    @Suppress("UNUSED_PARAMETER") deviceProfile: DeviceProfile,
    baseUrl: String,
    username: String,
    password: String,
) {
    val normalized = EmbyClientFactory.normalizeBaseUrl(baseUrl)
    val unauth: EmbyApi = EmbyClientFactory.create(normalized, accessToken = null)
    val auth = runEmbyHttpPhase("authenticateByName") {
        unauth.authenticateByName(AuthenticateByNameRequest(username, password))
    }
    val token = auth.accessToken ?: error("No access token")
    val userId = auth.user?.id ?: error("No user id")
    val api = EmbyClientFactory.create(normalized, token)
    val info = runEmbyHttpPhase("getSystemInfo") { api.getSystemInfo() }
    val display = info.serverName ?: normalized
    val fieldsJson = EmbyServerFieldsJson.encode(
        EmbyServerFieldsJson(
            baseUrl = normalized,
            userId = userId,
            accessToken = token,
            serverId = info.id,
        ),
    )
    serverStore.insert(
        providerId = OpenTuneProviderIds.HTTP_LIBRARY,
        displayName = display,
        fieldsJson = fieldsJson,
    )
}

private suspend fun updateHttpLibraryServer(
    serverStore: ServerStore,
    serverId: Long,
    displayNameInput: String,
    baseUrlInput: String,
    username: String,
    password: String,
    @Suppress("UNUSED_PARAMETER") deviceProfile: DeviceProfile,
) {
    val existing = serverStore.get(serverId) ?: error("Server not found")
    val normalized = EmbyClientFactory.normalizeBaseUrl(baseUrlInput)
    val unauth: EmbyApi = EmbyClientFactory.create(normalized, accessToken = null)
    val auth = runEmbyHttpPhase("authenticateByName") {
        unauth.authenticateByName(AuthenticateByNameRequest(username, password))
    }
    val token = auth.accessToken ?: error("No access token")
    val userId = auth.user?.id ?: error("No user id")
    val api = EmbyClientFactory.create(normalized, token)
    val info = runEmbyHttpPhase("getSystemInfo") { api.getSystemInfo() }
    val display = displayNameInput.ifBlank { info.serverName ?: normalized }
    val fieldsJson = EmbyServerFieldsJson.encode(
        EmbyServerFieldsJson(
            baseUrl = normalized,
            userId = userId,
            accessToken = token,
            serverId = info.id,
        ),
    )
    serverStore.update(
        sourceId = serverId,
        displayName = display,
        fieldsJson = fieldsJson,
    )
}
