package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.ValidationResult
import com.opentune.storage.ProxyConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

object ProxyConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun encodeFields(fields: Map<String, String>): String =
        json.encodeToString(stringMapSerializer, fields)

    suspend fun loadEditFields(
        proxyType: String,
        app: OpenTuneApplication,
        proxyConfigId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.proxyConfigDao.getById(proxyConfigId)
            ?: return@withContext emptyMap()
        val stored = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrElse { emptyMap() }
        val spec = app.proxyProviderRegistry.proxy(proxyType).getFieldsSpec()
        spec.associate { it.id to (stored[it.id] ?: "") }
    }

    suspend fun submitAdd(
        proxyType: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val proxyProvider = runCatching { app.proxyProviderRegistry.proxy(proxyType) }.getOrNull()
            ?: return@withContext SubmitResult.Error("Unknown proxy type: $proxyType")
        when (val result = proxyProvider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                val entity = ProxyConfigEntity(
                    id = UUID.randomUUID().toString(),
                    proxyType = proxyType,
                    displayName = result.name,
                    fieldsJson = encodeFields(result.fields),
                    isEnabled = true,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                try {
                    app.storageBindings.proxyConfigDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Proxy already exists")
                }
                SubmitResult.Success
            }
        }
    }

    suspend fun submitEdit(
        proxyType: String,
        proxyConfigId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val proxyProvider = runCatching { app.proxyProviderRegistry.proxy(proxyType) }.getOrNull()
            ?: return@withContext SubmitResult.Error("Unknown proxy type: $proxyType")
        val existing = app.storageBindings.proxyConfigDao.getById(proxyConfigId)
            ?: return@withContext SubmitResult.Error("Proxy not found")
        when (val result = proxyProvider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                app.storageBindings.proxyConfigDao.update(
                    existing.copy(
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                    )
                )
                invalidateAffectedHandles(proxyConfigId, app)
                SubmitResult.Success
            }
        }
    }

    suspend fun setEnabled(proxyConfigId: String, enabled: Boolean, app: OpenTuneApplication) =
        withContext(Dispatchers.IO) {
            val existing = app.storageBindings.proxyConfigDao.getById(proxyConfigId) ?: return@withContext
            app.storageBindings.proxyConfigDao.update(existing.copy(isEnabled = enabled))
            invalidateAffectedHandles(proxyConfigId, app)
        }

    suspend fun delete(proxyConfigId: String, app: OpenTuneApplication): Int =
        withContext(Dispatchers.IO) {
            val affected = app.storageBindings.proxyAssignmentDao.getEndpointIdsForProxy(proxyConfigId)
            app.storageBindings.proxyAssignmentDao.deleteByProxyConfigId(proxyConfigId)
            app.storageBindings.proxyConfigDao.deleteById(proxyConfigId)
            affected.forEach { app.endpointClientRegistry.remove(it) }
            affected.size
        }

    private suspend fun invalidateAffectedHandles(proxyConfigId: String, app: OpenTuneApplication) {
        val affected = app.storageBindings.proxyAssignmentDao.getEndpointIdsForProxy(proxyConfigId)
        affected.forEach { app.endpointClientRegistry.remove(it) }
    }
}
