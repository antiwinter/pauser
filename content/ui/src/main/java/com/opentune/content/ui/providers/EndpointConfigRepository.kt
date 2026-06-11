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
import java.security.MessageDigest

object EndpointConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private fun encodeFields(fields: Map<String, String>): String =
        json.encodeToString(stringMapSerializer, fields)

    // Hash only identity fields to produce a stable endpointId.
    // Non-identity fields (name, password) can change without creating a new endpoint.
    // Truncate to 16 hex digits (8 bytes) for shorter IDs.
    private fun computeHash(fields: Map<String, String>, identityKeys: Set<String>): String {
        val input = fields.entries
            .filter { it.key in identityKeys }
            .sortedBy { it.key }
            .joinToString { "${it.key}=${it.value}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    // Resolve display name: user input > suggested by provider > URL fallback.
    private fun resolveDisplayName(userInput: Map<String, String>, suggestedFields: Map<String, String>): String {
        // 1. User explicitly entered a name
        userInput["name"]?.takeIf { it.isNotBlank() }?.let { return it }
        // 2. Provider suggested a name
        suggestedFields["name"]?.takeIf { it.isNotBlank() }?.let { return it }
        // 3. Fallback to a URL-ish field
        for (key in listOf("url", "host", "server", "endpoint", "config_url", "api_url", "base_url")) {
            suggestedFields[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    // Merge user input with provider-suggested fields.
    // User values always win. Provider adds derived fields (e.g. user_id, access_token).
    private fun mergeFields(userInput: Map<String, String>, suggestedFields: Map<String, String>): Map<String, String> {
        return buildMap {
            putAll(suggestedFields)
            putAll(userInput)
        }
    }

    private suspend fun buildClient(protocol: String, values: Map<String, String>, proxyId: String?): EndpointClient {
        val provider   = OpenTuneProviderRegistryHolder.get().provider(protocol)
        val httpClient = EndpointClientRegistryHolder.get().buildHttpClient(proxyId)
        return provider.createClient(values)
            .also { it.httpClient = httpClient }
    }

    private fun identityKeys(protocol: String): Set<String> {
        return OpenTuneProviderRegistryHolder.get().provider(protocol)
            .getFieldsSpec()
            .filter { it.identity }
            .map { it.id }
            .toSet()
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
                val mergedFields = mergeFields(values, result.fields)
                val displayName = resolveDisplayName(values, result.fields)
                val hash = computeHash(mergedFields, identityKeys(protocol))
                val endpointId = "${protocol}_${hash}"
                val now = System.currentTimeMillis()
                val entity = EndpointEntity(
                    endpointId = endpointId,
                    protocol = protocol,
                    displayName = displayName,
                    fieldsJson = encodeFields(mergedFields),
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
                val mergedFields = mergeFields(values, result.fields)
                val displayName = resolveDisplayName(values, result.fields)
                val hash = computeHash(mergedFields, identityKeys(protocol))
                val newEndpointId = "${protocol}_${hash}"
                val now = System.currentTimeMillis()
                if (newEndpointId == endpointId) {
                    val existing = StorageBindingsHolder.get().endpointDao.getByEndpointId(endpointId)
                        ?: return@withContext SubmitResult.Error("Endpoint not found")
                    val updated = existing.copy(
                        displayName = displayName,
                        fieldsJson = encodeFields(mergedFields),
                        proxyId = proxyId,
                        updatedAtEpochMs = now,
                    )
                    StorageBindingsHolder.get().endpointDao.update(updated)
                    EndpointClientRegistryHolder.get().update(endpointId, updated)
                } else {
                    val newEntity = EndpointEntity(
                        endpointId = newEndpointId,
                        protocol = protocol,
                        displayName = displayName,
                        fieldsJson = encodeFields(mergedFields),
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
