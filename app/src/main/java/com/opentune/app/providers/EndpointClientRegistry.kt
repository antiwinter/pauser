package com.opentune.app.providers

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.proxy.ProxyProviderRegistry
import com.opentune.provider.EndpointClient
import com.opentune.storage.EndpointDao
import com.opentune.storage.EndpointEntity
import com.opentune.storage.ProxyDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class EndpointClientRegistry(
    private val endpointDao: EndpointDao,
    private val providerRegistry: OpenTuneProviderRegistry,
    private val proxyDao: ProxyDao,
    private val proxyProviderRegistry: ProxyProviderRegistry,
    private val sharedDiskCache: DiskCache,
    private val appContext: android.content.Context,
) {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, EndpointClient>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getOrCreate(endpointId: String): EndpointClient? = mutex.withLock {
        clients[endpointId] ?: run {
            val entity = endpointDao.getByEndpointId(endpointId) ?: return@withLock null
            val client = buildClient(entity) ?: return@withLock null
            clients[endpointId] = client
            client
        }
    }

    suspend fun registerHandle(endpointId: String, entity: EndpointEntity): EndpointClient? =
        mutex.withLock {
            val client = buildClient(entity) ?: return@withLock null
            clients[endpointId] = client
            client
        }

    suspend fun update(endpointId: String, entity: EndpointEntity): Unit = mutex.withLock {
        val client = buildClient(entity)
        if (client != null) clients[endpointId] = client else clients.remove(endpointId)
    }

    suspend fun remove(endpointId: String): Unit = mutex.withLock {
        clients.remove(endpointId)
    }

    suspend fun populateEager(entities: List<EndpointEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!clients.containsKey(entity.endpointId)) {
                val client = buildClient(entity) ?: continue
                clients[entity.endpointId] = client
            }
        }
    }

    suspend fun buildHttpClient(proxyId: String?): OkHttpClient =
        proxyId?.let { id ->
            runCatching {
                val proxy = proxyDao.getById(id) ?: return@runCatching null
                val proxyFields = json.decodeFromString<Map<String, String>>(proxy.fieldsJson)
                proxyProviderRegistry.proxy(proxy.proxyType).createClient(proxyFields)
            }.getOrNull()
        } ?: OkHttpClient()

    private suspend fun buildClient(entity: EndpointEntity): EndpointClient? {
        val provider = runCatching { providerRegistry.provider(entity.protocol) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null

        val httpClient: OkHttpClient = buildHttpClient(entity.proxyId)

        val client = runCatching {
            provider.createClient(values, providerRegistry.platformCapabilities)
        }.getOrNull() ?: return null

        client.httpClient = httpClient
        client.imageLoader = ImageLoader.Builder(appContext)
            .diskCache(sharedDiskCache)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = httpClient)) }
            .build()

        return client
    }
}
