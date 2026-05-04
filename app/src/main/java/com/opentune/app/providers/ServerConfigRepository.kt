package com.opentune.app.providers

import android.util.Log
import com.opentune.app.OpenTuneApplication
import com.opentune.app.drafts.EmbyAddDraft
import com.opentune.app.drafts.SmbAddDraft
import com.opentune.emby.api.EmbyApi
import com.opentune.emby.api.EmbyClientFactory
import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.runEmbyHttpPhase
import com.opentune.storage.EmbyServerEntity
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.SmbSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG = "OpenTuneServerConfig"

object ServerConfigRepository {

    suspend fun loadAddDraft(providerId: String, app: OpenTuneApplication): Map<String, String> =
        withContext(Dispatchers.IO) {
            when (providerId) {
                OpenTuneProviderIds.HTTP_LIBRARY -> {
                    val d = app.addServerDraftStore.loadEmby()
                    mapOf(
                        "base_url" to (d?.baseUrl ?: ""),
                        "username" to (d?.username ?: ""),
                        "password" to (d?.password ?: ""),
                    )
                }
                OpenTuneProviderIds.FILE_SHARE -> {
                    val d = app.addServerDraftStore.loadSmb()
                    mapOf(
                        "display_name" to (d?.displayName ?: ""),
                        "host" to (d?.host ?: ""),
                        "share_name" to (d?.shareName ?: ""),
                        "username" to (d?.username ?: ""),
                        "password" to (d?.password ?: ""),
                        "domain" to (d?.domain ?: ""),
                    )
                }
                else -> error("Unknown provider: $providerId")
            }
        }

    suspend fun saveAddDraft(providerId: String, app: OpenTuneApplication, values: Map<String, String>) {
        withContext(Dispatchers.IO) {
            when (providerId) {
                OpenTuneProviderIds.HTTP_LIBRARY -> {
                    app.addServerDraftStore.saveEmby(
                        EmbyAddDraft(
                            baseUrl = values["base_url"] ?: "",
                            username = values["username"] ?: "",
                            password = values["password"] ?: "",
                        ),
                    )
                }
                OpenTuneProviderIds.FILE_SHARE -> {
                    app.addServerDraftStore.saveSmb(
                        SmbAddDraft(
                            displayName = values["display_name"] ?: "",
                            host = values["host"] ?: "",
                            shareName = values["share_name"] ?: "",
                            username = values["username"] ?: "",
                            password = values["password"] ?: "",
                            domain = values["domain"] ?: "",
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    suspend fun clearAddDraft(providerId: String, app: OpenTuneApplication) {
        withContext(Dispatchers.IO) {
            when (providerId) {
                OpenTuneProviderIds.HTTP_LIBRARY -> app.addServerDraftStore.clearEmby()
                OpenTuneProviderIds.FILE_SHARE -> app.addServerDraftStore.clearSmb()
                else -> Unit
            }
        }
    }

    suspend fun loadEditFields(
        providerId: String,
        database: OpenTuneDatabase,
        sourceId: Long,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        when (providerId) {
            OpenTuneProviderIds.HTTP_LIBRARY -> {
                val row = database.embyServerDao().getById(sourceId) ?: return@withContext emptyMap()
                mapOf(
                    "display_name" to row.displayName,
                    "base_url" to row.baseUrl,
                    "username" to "",
                    "password" to "",
                )
            }
            OpenTuneProviderIds.FILE_SHARE -> {
                val row = database.smbSourceDao().getById(sourceId) ?: return@withContext emptyMap()
                mapOf(
                    "display_name" to row.displayName,
                    "host" to row.host,
                    "share_name" to row.shareName,
                    "username" to row.username,
                    "password" to row.password,
                    "domain" to (row.domain ?: ""),
                )
            }
            else -> error("Unknown provider: $providerId")
        }
    }

    suspend fun submitAdd(
        providerId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
        database: OpenTuneDatabase,
        deviceProfile: DeviceProfile,
    ) {
        when (providerId) {
            OpenTuneProviderIds.HTTP_LIBRARY -> addHttpLibraryServer(
                database = database,
                deviceProfile = deviceProfile,
                baseUrl = values["base_url"]?.trim().orEmpty(),
                username = values["username"]?.trim().orEmpty(),
                password = values["password"].orEmpty(),
            )
            OpenTuneProviderIds.FILE_SHARE -> {
                val name = values["display_name"].orEmpty()
                val host = values["host"]?.trim().orEmpty()
                val share = values["share_name"]?.trim().orEmpty()
                database.smbSourceDao().insert(
                    SmbSourceEntity(
                        displayName = name.ifBlank { share },
                        host = host,
                        shareName = share,
                        username = values["username"].orEmpty(),
                        password = values["password"].orEmpty(),
                        domain = values["domain"]?.trim()?.ifBlank { null },
                    ),
                )
            }
            else -> error("Unknown provider: $providerId")
        }
    }

    suspend fun submitEdit(
        providerId: String,
        sourceId: Long,
        values: Map<String, String>,
        database: OpenTuneDatabase,
        deviceProfile: DeviceProfile,
    ) {
        when (providerId) {
            OpenTuneProviderIds.HTTP_LIBRARY -> updateHttpLibraryServer(
                database = database,
                serverId = sourceId,
                displayNameInput = values["display_name"]?.trim().orEmpty(),
                baseUrlInput = values["base_url"]?.trim().orEmpty(),
                username = values["username"]?.trim().orEmpty(),
                password = values["password"].orEmpty(),
                deviceProfile = deviceProfile,
            )
            OpenTuneProviderIds.FILE_SHARE -> {
                database.smbSourceDao().update(
                    SmbSourceEntity(
                        id = sourceId,
                        displayName = values["display_name"].orEmpty().ifBlank { values["share_name"]?.trim().orEmpty() },
                        host = values["host"]?.trim().orEmpty(),
                        shareName = values["share_name"]?.trim().orEmpty(),
                        username = values["username"].orEmpty(),
                        password = values["password"].orEmpty(),
                        domain = values["domain"]?.trim()?.ifBlank { null },
                    ),
                )
            }
            else -> error("Unknown provider: $providerId")
        }
    }
}

private suspend fun addHttpLibraryServer(
    database: OpenTuneDatabase,
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
    val entity = EmbyServerEntity(
        displayName = display,
        baseUrl = normalized,
        userId = userId,
        accessToken = token,
        serverId = info.id,
    )
    database.embyServerDao().insert(entity)
    Log.w(LOG, "addHttpLibraryServer success displayName=$display")
}

private suspend fun updateHttpLibraryServer(
    database: OpenTuneDatabase,
    serverId: Long,
    displayNameInput: String,
    baseUrlInput: String,
    username: String,
    password: String,
    @Suppress("UNUSED_PARAMETER") deviceProfile: DeviceProfile,
) {
    val existing = database.embyServerDao().getById(serverId) ?: error("Server not found")
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
    val entity = existing.copy(
        displayName = display,
        baseUrl = normalized,
        userId = userId,
        accessToken = token,
        serverId = info.id,
    )
    database.embyServerDao().update(entity)
    Log.w(LOG, "updateHttpLibraryServer success serverId=$serverId")
}
