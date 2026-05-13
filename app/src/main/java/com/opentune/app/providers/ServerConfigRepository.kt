package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.SubmitResult
import com.opentune.provider.ValidationResult
import com.opentune.storage.ServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object ServerConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Draft storage via DataStore ---

    suspend fun loadAddDraft(protocol: String, app: OpenTuneApplication): Map<String, String> =
        app.storageBindings.appConfigStore.loadDraft(protocol)

    suspend fun saveAddDraft(protocol: String, app: OpenTuneApplication, values: Map<String, String>) =
        app.storageBindings.appConfigStore.saveDraft(protocol, values)

    suspend fun clearAddDraft(protocol: String, app: OpenTuneApplication) =
        app.storageBindings.appConfigStore.clearDraft(protocol)

    // --- Server add ---

    suspend fun submitAdd(
        protocol: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(protocol)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val sourceId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                val entity = ServerEntity(
                    sourceId = sourceId,
                    protocol = protocol,
                    displayName = result.displayName,
                    fieldsJson = result.fieldsJson,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                try {
                    app.storageBindings.serverDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Server already exists")
                }
                app.instanceRegistry.createAndRegister(sourceId, entity)
                SubmitResult.Success
            }
        }
    }

    // --- Server edit ---

    suspend fun loadEditFields(
        protocol: String,
        app: OpenTuneApplication,
        sourceId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.serverDao.getBySourceId(sourceId) ?: return@withContext emptyMap()
        val stored = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrElse { emptyMap() }
        val spec = app.providerRegistry.provider(protocol).getFieldsSpec()
        spec.associate { it.id to (stored[it.id] ?: "") }
    }

    suspend fun submitEdit(
        protocol: String,
        sourceId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(protocol)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val newSourceId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                if (newSourceId == sourceId) {
                    // Same identity — update fields only
                    val existing = app.storageBindings.serverDao.getBySourceId(sourceId)
                        ?: return@withContext SubmitResult.Error("Server not found")
                    app.storageBindings.serverDao.update(
                        existing.copy(
                            displayName = result.displayName,
                            fieldsJson = result.fieldsJson,
                            updatedAtEpochMs = now,
                        ),
                    )
                    app.instanceRegistry.update(sourceId, existing.copy(
                        displayName = result.displayName,
                        fieldsJson = result.fieldsJson,
                        updatedAtEpochMs = now,
                    ))
                } else {
                    // Identity changed — insert new, cascade-delete old
                    val newEntity = ServerEntity(
                        sourceId = newSourceId,
                        protocol = protocol,
                        displayName = result.displayName,
                        fieldsJson = result.fieldsJson,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                    try {
                        app.storageBindings.serverDao.insert(newEntity)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        return@withContext SubmitResult.Error("A server with the new credentials already exists")
                    }
                    app.instanceRegistry.createAndRegister(newSourceId, newEntity)
                    app.storageBindings.mediaStateStore.deleteBySource(sourceId)
                    app.storageBindings.thumbnailDiskCache.deleteBySource(sourceId)
                    app.storageBindings.serverDao.deleteBySourceId(sourceId)
                    app.instanceRegistry.remove(sourceId)
                }
                SubmitResult.Success
            }
        }
    }

    // --- Server removal ---

    suspend fun removeServer(sourceId: String, app: OpenTuneApplication) =
        withContext(Dispatchers.IO) {
            app.storageBindings.mediaStateStore.deleteBySource(sourceId)
            app.storageBindings.thumbnailDiskCache.deleteBySource(sourceId)
            app.storageBindings.serverDao.deleteBySourceId(sourceId)
            app.instanceRegistry.remove(sourceId)
        }
}
