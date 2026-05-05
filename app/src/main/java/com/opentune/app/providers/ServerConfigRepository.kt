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

    suspend fun loadAddDraft(providerType: String, app: OpenTuneApplication): Map<String, String> =
        app.storageBindings.appConfigStore.loadDraft(providerType)

    suspend fun saveAddDraft(providerType: String, app: OpenTuneApplication, values: Map<String, String>) =
        app.storageBindings.appConfigStore.saveDraft(providerType, values)

    suspend fun clearAddDraft(providerType: String, app: OpenTuneApplication) =
        app.storageBindings.appConfigStore.clearDraft(providerType)

    // --- Server add ---

    suspend fun submitAdd(
        providerType: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(providerType)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val sourceId = "${providerType}_${result.hash}"
                val now = System.currentTimeMillis()
                val entity = ServerEntity(
                    sourceId = sourceId,
                    providerType = providerType,
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
        providerType: String,
        app: OpenTuneApplication,
        sourceId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.serverDao.getBySourceId(sourceId) ?: return@withContext emptyMap()
        val stored = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrElse { emptyMap() }
        val spec = app.providerRegistry.provider(providerType).getFieldsSpec()
        spec.associate { it.id to (stored[it.id] ?: "") }
    }

    suspend fun submitEdit(
        providerType: String,
        sourceId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val provider = app.providerRegistry.provider(providerType)
        when (val result = provider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val newSourceId = "${providerType}_${result.hash}"
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
                        providerType = providerType,
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
