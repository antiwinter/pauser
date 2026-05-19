package com.opentune.app.providers

import com.opentune.provider.EndpointClient
import com.opentune.storage.EndpointEntity
import com.opentune.storage.EndpointDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class EndpointClientRegistry(
    private val endpointDao: EndpointDao,
    private val providerRegistry: OpenTuneProviderRegistry,
) {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, EndpointClient>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Get existing or create a new client by lazy DB lookup. Returns null if endpointId is unknown. */
    suspend fun getOrCreate(endpointId: String): EndpointClient? = mutex.withLock {
        clients[endpointId] ?: run {
            val entity = endpointDao.getByEndpointId(endpointId) ?: return@withLock null
            val client = buildClient(entity) ?: return@withLock null
            clients[endpointId] = client
            client
        }
    }

    /** Register a client immediately after endpoint creation. */
    suspend fun registerClient(endpointId: String, entity: EndpointEntity): EndpointClient? =
        mutex.withLock {
            val client = buildClient(entity) ?: return@withLock null
            clients[endpointId] = client
            client
        }

    /** Re-register a client when credentials are updated. */
    suspend fun update(endpointId: String, entity: EndpointEntity): Unit = mutex.withLock {
        val client = buildClient(entity)
        if (client != null) clients[endpointId] = client else clients.remove(endpointId)
    }

    /** Remove a client when an endpoint is deleted. */
    suspend fun remove(endpointId: String): Unit = mutex.withLock {
        clients.remove(endpointId)
    }

    /** Eagerly populate registry from a snapshot of endpoints (called from home screen). */
    suspend fun populateEager(entities: List<EndpointEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!clients.containsKey(entity.endpointId)) {
                val client = buildClient(entity) ?: continue
                clients[entity.endpointId] = client
            }
        }
    }

    private fun buildClient(entity: EndpointEntity): EndpointClient? {
        val provider = runCatching { providerRegistry.provider(entity.protocol) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null
        return runCatching { provider.createClient(values, providerRegistry.platformCapabilities) }.getOrNull()
    }
}
