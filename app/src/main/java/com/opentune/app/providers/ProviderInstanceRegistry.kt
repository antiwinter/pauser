package com.opentune.app.providers

import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.ServerEntity
import com.opentune.storage.ServerDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ProviderInstanceRegistry(
    private val serverDao: ServerDao,
    private val providerRegistry: OpenTuneProviderRegistry,
) {
    private val mutex = Mutex()
    private val instances = mutableMapOf<String, OpenTuneProviderInstance>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Get existing or create a new instance by lazy DB lookup. Returns null if sourceId is unknown. */
    suspend fun getOrCreate(sourceId: String): OpenTuneProviderInstance? = mutex.withLock {
        instances[sourceId] ?: run {
            val entity = serverDao.getBySourceId(sourceId) ?: return@withLock null
            val instance = buildInstance(entity) ?: return@withLock null
            instances[sourceId] = instance
            instance
        }
    }

    /** Register an instance immediately after server creation. */
    suspend fun createAndRegister(sourceId: String, entity: ServerEntity): OpenTuneProviderInstance? =
        mutex.withLock {
            val instance = buildInstance(entity) ?: return@withLock null
            instances[sourceId] = instance
            instance
        }

    /** Re-register an instance when credentials are updated. */
    suspend fun update(sourceId: String, entity: ServerEntity): Unit = mutex.withLock {
        val instance = buildInstance(entity)
        if (instance != null) instances[sourceId] = instance else instances.remove(sourceId)
    }

    /** Remove an instance when a server is deleted. */
    suspend fun remove(sourceId: String): Unit = mutex.withLock {
        instances.remove(sourceId)
    }

    /** Eagerly populate registry from a snapshot of servers (called from home screen). */
    suspend fun populateEager(entities: List<ServerEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!instances.containsKey(entity.sourceId)) {
                val instance = buildInstance(entity) ?: continue
                instances[entity.sourceId] = instance
            }
        }
    }

    private fun buildInstance(entity: ServerEntity): OpenTuneProviderInstance? {
        val provider = runCatching { providerRegistry.provider(entity.providerType) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null
        return runCatching { provider.createInstance(values) }.getOrNull()
    }
}
