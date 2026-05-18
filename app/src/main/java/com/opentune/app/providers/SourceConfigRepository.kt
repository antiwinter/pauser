package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.ValidationResult
import com.opentune.storage.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object SourceConfigRepository {

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

    // --- Source add ---

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
                val entity = SourceEntity(
                    sourceId = sourceId,
                    protocol = protocol,
                    displayName = result.name,
                    fieldsJson = encodeFields(result.fields),
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                try {
                    app.storageBindings.sourceDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Source already exists")
                }
                app.instanceRegistry.createAndRegister(sourceId, entity)
                SubmitResult.Success
            }
        }
    }

    // --- Source edit ---

    suspend fun loadEditFields(
        protocol: String,
        app: OpenTuneApplication,
        sourceId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.sourceDao.getBySourceId(sourceId) ?: return@withContext emptyMap()
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
                    val existing = app.storageBindings.sourceDao.getBySourceId(sourceId)
                        ?: return@withContext SubmitResult.Error("Source not found")
                    app.storageBindings.sourceDao.update(
                        existing.copy(
                            displayName = result.name,
                            fieldsJson = encodeFields(result.fields),
                            updatedAtEpochMs = now,
                        ),
                    )
                    app.instanceRegistry.update(sourceId, existing.copy(
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        updatedAtEpochMs = now,
                    ))
                } else {
                    // Identity changed — insert new, cascade-delete old
                    val newEntity = SourceEntity(
                        sourceId = newSourceId,
                        protocol = protocol,
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                    try {
                        app.storageBindings.sourceDao.insert(newEntity)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        return@withContext SubmitResult.Error("A source with the new credentials already exists")
                    }
                    app.instanceRegistry.createAndRegister(newSourceId, newEntity)
                    app.storageBindings.mediaStateStore.deleteBySource(sourceId)
                    app.storageBindings.sourceDao.deleteBySourceId(sourceId)
                    app.instanceRegistry.remove(sourceId)
                }
                SubmitResult.Success
            }
        }
    }

    // --- Source removal ---

    suspend fun removeSource(sourceId: String, app: OpenTuneApplication) =
        withContext(Dispatchers.IO) {
            app.storageBindings.mediaStateStore.deleteBySource(sourceId)
            app.storageBindings.sourceDao.deleteBySourceId(sourceId)
            app.instanceRegistry.remove(sourceId)
        }
}
