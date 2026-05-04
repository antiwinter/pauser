package com.opentune.smb

import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.ProviderConfigBackend
import com.opentune.provider.ServerStore
import com.opentune.provider.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbConfigBackend : ProviderConfigBackend {

    override suspend fun submitAdd(values: Map<String, String>, serverStore: ServerStore): SubmitResult =
        withContext(Dispatchers.IO) {
            try {
                val name = values["display_name"].orEmpty()
                val host = values["host"]?.trim().orEmpty()
                val share = values["share_name"]?.trim().orEmpty()
                val fields = SmbServerFieldsJson(
                    host = host,
                    shareName = share,
                    username = values["username"].orEmpty(),
                    password = values["password"].orEmpty(),
                    domain = values["domain"]?.trim()?.ifBlank { null },
                )
                serverStore.insert(
                    providerId = OpenTuneProviderIds.FILE_SHARE,
                    displayName = name.ifBlank { share },
                    fieldsJson = SmbServerFieldsJson.encode(fields),
                )
                SubmitResult.Success
            } catch (e: Exception) {
                SubmitResult.Error(e.message ?: "Add share failed")
            }
        }

    override suspend fun submitEdit(
        sourceId: Long,
        values: Map<String, String>,
        serverStore: ServerStore,
    ): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val fields = SmbServerFieldsJson(
                host = values["host"]?.trim().orEmpty(),
                shareName = values["share_name"]?.trim().orEmpty(),
                username = values["username"].orEmpty(),
                password = values["password"].orEmpty(),
                domain = values["domain"]?.trim()?.ifBlank { null },
            )
            val display = values["display_name"].orEmpty().ifBlank { fields.shareName }
            serverStore.update(
                sourceId = sourceId,
                displayName = display,
                fieldsJson = SmbServerFieldsJson.encode(fields),
            )
            SubmitResult.Success
        } catch (e: Exception) {
            SubmitResult.Error(e.message ?: "Update share failed")
        }
    }

    override suspend fun loadEditFields(sourceId: Long, serverStore: ServerStore): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val row = serverStore.get(sourceId) ?: return@withContext null
            val f = SmbServerFieldsJson.parse(row.fieldsJson)
            mapOf(
                "display_name" to row.displayName,
                "host" to f.host,
                "share_name" to f.shareName,
                "username" to f.username,
                "password" to f.password,
                "domain" to (f.domain ?: ""),
            )
        }
}
