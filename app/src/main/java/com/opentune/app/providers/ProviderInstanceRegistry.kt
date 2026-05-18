package com.opentune.app.providers

import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.SourceEntity
import com.opentune.storage.SourceDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ProviderInstanceRegistry(
    private val sourceDao: SourceDao,
    private val providerRegistry: OpenTuneProviderRegistry,
) {
    private val mutex = Mutex()
    private val instances = mutableMapOf<String, OpenTuneProviderInstance>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Get existing or create a new instance by lazy DB lookup. Returns null if sourceId is unknown. */
    suspend fun getOrCreate(sourceId: String): OpenTuneProviderInstance? = mutex.withLock {
        instances[sourceId] ?: run {
            val entity = sourceDao.getBySourceId(sourceId) ?: return@withLock null
            val instance = buildInstance(entity) ?: return@withLock null
            instances[sourceId] = instance
            instance
        }
    }

    /** Register an instance immediately after source creation. */
    suspend fun createAndRegister(sourceId: String, entity: SourceEntity): OpenTuneProviderInstance? =
        mutex.withLock {
            val instance = buildInstance(entity) ?: return@withLock null
            instances[sourceId] = instance
            instance
        }

    /** Re-register an instance when credentials are updated. */
    suspend fun update(sourceId: String, entity: SourceEntity): Unit = mutex.withLock {
        val instance = buildInstance(entity)
        if (instance != null) instances[sourceId] = instance else instances.remove(sourceId)
    }

    /** Remove an instance when a source is deleted. */
    suspend fun remove(sourceId: String): Unit = mutex.withLock {
        instances.remove(sourceId)
    }

    /** Eagerly populate registry from a snapshot of sources (called from home screen). */
    suspend fun populateEager(entities: List<SourceEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!instances.containsKey(entity.sourceId)) {
                val instance = buildInstance(entity) ?: continue
                instances[entity.sourceId] = instance
            }
        }
    }

    private fun buildInstance(entity: SourceEntity): OpenTuneProviderInstance? {
        val provider = runCatching { providerRegistry.provider(entity.protocol) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null
        return runCatching { provider.createInstance(values, providerRegistry.platformCapabilities) }.getOrNull()
    }
}
