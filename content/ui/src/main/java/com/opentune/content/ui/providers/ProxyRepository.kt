package com.opentune.content.ui.providers

import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.proxy.contract.ProxyProviderRegistryHolder
import com.opentune.proxy.contract.ProxyValidationResult
import com.opentune.storage.ProxyEntity
import com.opentune.storage.StorageBindingsHolder
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

    suspend fun loadEditFields(proxyType: String, proxyId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val entity = StorageBindingsHolder.get().proxyDao.getById(proxyId)
                ?: return@withContext emptyMap()
            val stored = runCatching {
                json.decodeFromString<Map<String, String>>(entity.fieldsJson)
            }.getOrElse { emptyMap() }
            val spec = ProxyProviderRegistryHolder.get().proxy(proxyType).getFieldsSpec()
            spec.associate { it.id to (stored[it.id] ?: "") }
        }

    suspend fun submitAdd(proxyType: String, values: Map<String, String>): SubmitResult =
        withContext(Dispatchers.IO) {
            val proxyProvider = runCatching { ProxyProviderRegistryHolder.get().proxy(proxyType) }.getOrNull()
                ?: return@withContext SubmitResult.Error("Unknown proxy type: $proxyType")
            when (val result = proxyProvider.validateFields(values)) {
                is ProxyValidationResult.Error -> SubmitResult.Error(result.message)
                is ProxyValidationResult.Success -> {
                    val entity = ProxyEntity(
                        id = UUID.randomUUID().toString(),
                        proxyType = proxyType,
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                    try {
                        StorageBindingsHolder.get().proxyDao.insert(entity)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        return@withContext SubmitResult.Error("Proxy already exists")
                    }
                    SubmitResult.Success
                }
            }
        }

    suspend fun submitEdit(proxyType: String, proxyId: String, values: Map<String, String>): SubmitResult =
        withContext(Dispatchers.IO) {
            val proxyProvider = runCatching { ProxyProviderRegistryHolder.get().proxy(proxyType) }.getOrNull()
                ?: return@withContext SubmitResult.Error("Unknown proxy type: $proxyType")
            val existing = StorageBindingsHolder.get().proxyDao.getById(proxyId)
                ?: return@withContext SubmitResult.Error("Proxy not found")
            when (val result = proxyProvider.validateFields(values)) {
                is ProxyValidationResult.Error -> SubmitResult.Error(result.message)
                is ProxyValidationResult.Success -> {
                    StorageBindingsHolder.get().proxyDao.update(
                        existing.copy(displayName = result.name, fieldsJson = encodeFields(result.fields))
                    )
                    val affected = StorageBindingsHolder.get().endpointDao.getByProxyId(proxyId)
                    affected.forEach { EndpointClientRegistryHolder.get().remove(it.endpointId) }
                    SubmitResult.Success
                }
            }
        }

    suspend fun delete(proxyId: String): Int = withContext(Dispatchers.IO) {
        val affected = StorageBindingsHolder.get().endpointDao.getByProxyId(proxyId)
        affected.forEach { entity ->
            StorageBindingsHolder.get().endpointDao.update(entity.copy(proxyId = null))
            EndpointClientRegistryHolder.get().remove(entity.endpointId)
        }
        StorageBindingsHolder.get().proxyDao.deleteById(proxyId)
        affected.size
    }
}
