package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.ValidationResult
import com.opentune.storage.EndpointEntity
import com.opentune.storage.ProxyAssignmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object EndpointConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun encodeFields(fields: Map<String, String>): String =
        json.encodeToString(stringMapSerializer, fields)

    // --- Draft storage via DataStore ---

    suspend fun loadAddDraft(protocol: String, app: OpenTuneApplication): Map<String, String> =
        app.storageBindings.appConfigStore.loadDraft(protocol)

    suspend fun saveAddDraft(protocol: String, app: OpenTuneApplication, values: Map<String, String>) =
        app.storageBindings.appConfigStore.saveDraft(protocol, values)

    suspend fun clearAddDraft(protocol: String, app: OpenTuneApplication) =
        app.storageBindings.appConfigStore.clearDraft(protocol)

    // --- Endpoint add ---

    suspend fun submitAdd(
        protocol: String,
        values: Map<String, String>,
        proxyConfigId: String?,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(protocol)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val endpointId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                val entity = EndpointEntity(
                    endpointId = endpointId,
                    protocol = protocol,
                    displayName = result.name,
                    fieldsJson = encodeFields(result.fields),
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                try {
                    app.storageBindings.endpointDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Endpoint already exists")
                }
                app.storageBindings.proxyAssignmentDao.upsert(
                    ProxyAssignmentEntity(endpointId = endpointId, proxyConfigId = proxyConfigId)
                )
                app.endpointClientRegistry.registerHandle(endpointId, entity)
                SubmitResult.Success
            }
        }
    }

    // --- Endpoint edit ---

    suspend fun loadEditFields(
        protocol: String,
        app: OpenTuneApplication,
        endpointId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.endpointDao.getByEndpointId(endpointId) ?: return@withContext emptyMap()
        val stored = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrElse { emptyMap() }
        val spec = app.providerRegistry.provider(protocol).getFieldsSpec()
        spec.associate { it.id to (stored[it.id] ?: "") }
    }

    suspend fun loadEditProxyConfigId(endpointId: String, app: OpenTuneApplication): String? =
        withContext(Dispatchers.IO) {
            app.storageBindings.proxyAssignmentDao.getByEndpointId(endpointId)?.proxyConfigId
        }

    suspend fun submitEdit(
        protocol: String,
        endpointId: String,
        values: Map<String, String>,
        proxyConfigId: String?,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(protocol)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val newEndpointId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                if (newEndpointId == endpointId) {
                    val existing = app.storageBindings.endpointDao.getByEndpointId(endpointId)
                        ?: return@withContext SubmitResult.Error("Endpoint not found")
                    val updated = existing.copy(
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        updatedAtEpochMs = now,
                    )
                    app.storageBindings.endpointDao.update(updated)
                    app.storageBindings.proxyAssignmentDao.upsert(
                        ProxyAssignmentEntity(endpointId = endpointId, proxyConfigId = proxyConfigId)
                    )
                    app.endpointClientRegistry.update(endpointId, updated)
                } else {
                    val newEntity = EndpointEntity(
                        endpointId = newEndpointId,
                        protocol = protocol,
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                    try {
                        app.storageBindings.endpointDao.insert(newEntity)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        return@withContext SubmitResult.Error("An endpoint with the new credentials already exists")
                    }
                    app.storageBindings.proxyAssignmentDao.upsert(
                        ProxyAssignmentEntity(endpointId = newEndpointId, proxyConfigId = proxyConfigId)
                    )
                    app.endpointClientRegistry.registerHandle(newEndpointId, newEntity)
                    app.storageBindings.entryStateStore.deleteByEndpoint(endpointId)
                    app.storageBindings.proxyAssignmentDao.deleteByEndpointId(endpointId)
                    app.storageBindings.endpointDao.deleteByEndpointId(endpointId)
                    app.endpointClientRegistry.remove(endpointId)
                }
                SubmitResult.Success
            }
        }
    }

    // --- Endpoint removal ---

    suspend fun removeEndpoint(endpointId: String, app: OpenTuneApplication) =
        withContext(Dispatchers.IO) {
            app.storageBindings.entryStateStore.deleteByEndpoint(endpointId)
            app.storageBindings.proxyAssignmentDao.deleteByEndpointId(endpointId)
            app.storageBindings.endpointDao.deleteByEndpointId(endpointId)
            app.endpointClientRegistry.remove(endpointId)
        }
}
