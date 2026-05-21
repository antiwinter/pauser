package com.opentune.app.providers

import com.opentune.proxy.ProxyProviderRegistry
import com.opentune.storage.EndpointDao
import com.opentune.storage.EndpointEntity
import com.opentune.storage.ProxyAssignmentDao
import com.opentune.storage.ProxyConfigDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class EndpointClientRegistry(
    private val endpointDao: EndpointDao,
    private val providerRegistry: OpenTuneProviderRegistry,
    private val proxyConfigDao: ProxyConfigDao,
    private val proxyAssignmentDao: ProxyAssignmentDao,
    private val proxyProviderRegistry: ProxyProviderRegistry,
) {
    private val mutex = Mutex()
    private val handles = mutableMapOf<String, EndpointHandle>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getOrCreate(endpointId: String): EndpointHandle? = mutex.withLock {
        handles[endpointId] ?: run {
            val entity = endpointDao.getByEndpointId(endpointId) ?: return@withLock null
            val handle = buildHandle(entity) ?: return@withLock null
            handles[endpointId] = handle
            handle
        }
    }

    suspend fun registerHandle(endpointId: String, entity: EndpointEntity): EndpointHandle? =
        mutex.withLock {
            val handle = buildHandle(entity) ?: return@withLock null
            handles[endpointId] = handle
            handle
        }

    suspend fun update(endpointId: String, entity: EndpointEntity): Unit = mutex.withLock {
        val handle = buildHandle(entity)
        if (handle != null) handles[endpointId] = handle else handles.remove(endpointId)
    }

    suspend fun remove(endpointId: String): Unit = mutex.withLock {
        handles.remove(endpointId)
    }

    suspend fun populateEager(entities: List<EndpointEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!handles.containsKey(entity.endpointId)) {
                val handle = buildHandle(entity) ?: continue
                handles[entity.endpointId] = handle
            }
        }
    }

    private suspend fun buildHandle(entity: EndpointEntity): EndpointHandle? {
        val provider = runCatching { providerRegistry.provider(entity.protocol) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null
        val client = runCatching {
            provider.createClient(values, providerRegistry.platformCapabilities)
        }.getOrNull() ?: return null

        val httpClient = if (provider.supportsProxy) {
            runCatching {
                val assignment = proxyAssignmentDao.getByEndpointId(entity.endpointId)
                    ?: return@runCatching null
                val proxyConfigId = assignment.proxyConfigId ?: return@runCatching null
                val proxyConfig = proxyConfigDao.getById(proxyConfigId)
                    ?: return@runCatching null
                if (!proxyConfig.isEnabled) return@runCatching null
                val proxyFields = json.decodeFromString<Map<String, String>>(proxyConfig.fieldsJson)
                val proxyProvider = runCatching {
                    proxyProviderRegistry.proxy(proxyConfig.proxyType)
                }.getOrNull() ?: return@runCatching null
                proxyProvider.createClient(proxyFields)
            }.getOrNull()
        } else null

        return EndpointHandle(client, httpClient)
    }
}
