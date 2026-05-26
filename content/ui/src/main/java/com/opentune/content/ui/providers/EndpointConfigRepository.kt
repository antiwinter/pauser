package com.opentune.content.ui.providers

import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EndpointValidationResult
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.storage.EndpointEntity
import com.opentune.core.form.SubmitResult
import com.opentune.storage.StorageBindingsHolder
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

    private suspend fun buildClient(protocol: String, values: Map<String, String>, proxyId: String?): EndpointClient {
        val provider   = OpenTuneProviderRegistryHolder.get().provider(protocol)
        val httpClient = EndpointClientRegistryHolder.get().buildHttpClient(proxyId)
        return provider.createClient(values, OpenTuneProviderRegistryHolder.get().platformCapabilities)
            .also { it.httpClient = httpClient }
    }

    suspend fun loadAddDraft(protocol: String): Map<String, String> =
        StorageBindingsHolder.get().appConfigStore.loadDraft(protocol)

    suspend fun saveAddDraft(protocol: String, values: Map<String, String>) =
        StorageBindingsHolder.get().appConfigStore.saveDraft(protocol, values)

    suspend fun clearAddDraft(protocol: String) =
        StorageBindingsHolder.get().appConfigStore.clearDraft(protocol)

    suspend fun submitAdd(
        protocol: String,
        values: Map<String, String>,
        proxyId: String?,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val client = runCatching {
            buildClient(protocol, values, proxyId)
        }.getOrElse { return@withContext SubmitResult.Error(it.message ?: "Failed to create client") }
        when (val result = client.test()) {
            is EndpointValidationResult.Error -> SubmitResult.Error(result.message)
            is EndpointValidationResult.Success -> {
                val endpointId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                val entity = EndpointEntity(
                    endpointId = endpointId,
                    protocol = protocol,
                    displayName = result.name,
                    fieldsJson = encodeFields(result.fields),
                    proxyId = proxyId,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                try {
                    StorageBindingsHolder.get().endpointDao.insert(entity)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    return@withContext SubmitResult.Error("Endpoint already exists")
                }
                EndpointClientRegistryHolder.get().registerHandle(endpointId, entity)
                SubmitResult.Success
            }
        }
    }

    suspend fun loadEditFields(protocol: String, endpointId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val entity = StorageBindingsHolder.get().endpointDao.getByEndpointId(endpointId)
                ?: return@withContext emptyMap()
            val stored = runCatching {
                json.decodeFromString<Map<String, String>>(entity.fieldsJson)
            }.getOrElse { emptyMap() }
            val spec = OpenTuneProviderRegistryHolder.get().provider(protocol).getFieldsSpec()
            spec.associate { it.id to (stored[it.id] ?: "") }
        }

    suspend fun loadEditProxyId(endpointId: String): String? =
        withContext(Dispatchers.IO) {
            StorageBindingsHolder.get().endpointDao.getByEndpointId(endpointId)?.proxyId
        }

    suspend fun submitEdit(
        protocol: String,
        endpointId: String,
        values: Map<String, String>,
        proxyId: String?,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val client = runCatching {
            buildClient(protocol, values, proxyId)
        }.getOrElse { return@withContext SubmitResult.Error(it.message ?: "Failed to create client") }
        when (val result = client.test()) {
            is EndpointValidationResult.Error -> SubmitResult.Error(result.message)
            is EndpointValidationResult.Success -> {
                val newEndpointId = "${protocol}_${result.hash}"
                val now = System.currentTimeMillis()
                if (newEndpointId == endpointId) {
                    val existing = StorageBindingsHolder.get().endpointDao.getByEndpointId(endpointId)
                        ?: return@withContext SubmitResult.Error("Endpoint not found")
                    val updated = existing.copy(
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        proxyId = proxyId,
                        updatedAtEpochMs = now,
                    )
                    StorageBindingsHolder.get().endpointDao.update(updated)
                    EndpointClientRegistryHolder.get().update(endpointId, updated)
                } else {
                    val newEntity = EndpointEntity(
                        endpointId = newEndpointId,
                        protocol = protocol,
                        displayName = result.name,
                        fieldsJson = encodeFields(result.fields),
                        proxyId = proxyId,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                    try {
                        StorageBindingsHolder.get().endpointDao.insert(newEntity)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        return@withContext SubmitResult.Error("An endpoint with the new credentials already exists")
                    }
                    EndpointClientRegistryHolder.get().registerHandle(newEndpointId, newEntity)
                    StorageBindingsHolder.get().entryStateStore.deleteByEndpoint(endpointId)
                    StorageBindingsHolder.get().endpointDao.deleteByEndpointId(endpointId)
                    EndpointClientRegistryHolder.get().remove(endpointId)
                }
                SubmitResult.Success
            }
        }
    }

    suspend fun removeEndpoint(endpointId: String) = withContext(Dispatchers.IO) {
        StorageBindingsHolder.get().entryStateStore.deleteByEndpoint(endpointId)
        StorageBindingsHolder.get().endpointDao.deleteByEndpointId(endpointId)
        EndpointClientRegistryHolder.get().remove(endpointId)
    }
}
