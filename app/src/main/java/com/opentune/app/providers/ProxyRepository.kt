package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.ValidationResult
import com.opentune.storage.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

object ProxyRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun encodeFields(fields: Map<String, String>): String =
        json.encodeToString(stringMapSerializer, fields)

    suspend fun loadEditFields(
        proxyType: String,
        app: OpenTuneApplication,
        proxyId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val entity = app.storageBindings.proxyDao.getById(proxyId)
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
                val entity = ProxyEntity(
                    id = UUID.randomUUID().toString(),
                    proxyType = proxyType,
                    displayName = result.name,
                    fieldsJson = encodeFields(result.fields),
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                try {
                    app.storageBindings.proxyDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Proxy already exists")
                }
                SubmitResult.Success
            }
        }
    }

    suspend fun submitEdit(
        proxyType: String,
        proxyId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val proxyProvider = runCatching { app.proxyProviderRegistry.proxy(proxyType) }.getOrNull()
            ?: return@withContext SubmitResult.Error("Unknown proxy type: $proxyType")
        val existing = app.storageBindings.proxyDao.getById(proxyId)
            ?: return@withContext SubmitResult.Error("Proxy not found")
        when (val result = proxyProvider.validateFields(values)) {
            is ValidationResult.Error -> SubmitResult.Error(result.message)
            is ValidationResult.Success -> {
                app.storageBindings.proxyDao.update(
                    existing.copy(
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                    )
                )
                invalidateAffectedEndpoints(proxyId, app)
                SubmitResult.Success
            }
        }
    }

    suspend fun delete(proxyId: String, app: OpenTuneApplication): Int =
        withContext(Dispatchers.IO) {
            val affected = app.storageBindings.endpointDao.getByProxyId(proxyId)
            affected.forEach { entity ->
                app.storageBindings.endpointDao.update(entity.copy(proxyId = null))
                app.endpointClientRegistry.remove(entity.endpointId)
            }
            app.storageBindings.proxyDao.deleteById(proxyId)
            affected.size
        }

    private suspend fun invalidateAffectedEndpoints(proxyId: String, app: OpenTuneApplication) {
        val affected = app.storageBindings.endpointDao.getByProxyId(proxyId)
        affected.forEach { app.endpointClientRegistry.remove(it.endpointId) }
    }
}
