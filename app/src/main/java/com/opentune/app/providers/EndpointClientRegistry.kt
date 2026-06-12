package com.opentune.app.providers

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.content.contract.OpenTuneProviderRegistry
import com.opentune.proxy.contract.ProxyProviderRegistry
import com.opentune.content.contract.CachingEndpointClient
import com.opentune.content.contract.EndpointCache
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientAccess
import com.opentune.proxy.contract.ProxyClient
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
) : EndpointClientAccess {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, EndpointClient>()
    private val proxyClients = mutableMapOf<String, ProxyClient>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getOrCreate(endpointId: String): EndpointClient? = mutex.withLock {
        clients[endpointId] ?: run {
            val entity = endpointDao.getByEndpointId(endpointId) ?: return@withLock null
            val client = buildClient(entity) ?: return@withLock null
            val wrapped = CachingEndpointClient(client)
            clients[endpointId] = wrapped
            wrapped
        }
    }

    override suspend fun registerHandle(endpointId: String, entity: EndpointEntity): EndpointClient? =
        mutex.withLock {
            val client = buildClient(entity) ?: return@withLock null
            val wrapped = CachingEndpointClient(client)
            clients[endpointId] = wrapped
            wrapped
        }

    override suspend fun update(endpointId: String, entity: EndpointEntity): Unit = mutex.withLock {
        EndpointCache.clearForEndpoint(endpointId)
        val client = buildClient(entity)
        if (client != null) {
            val wrapped = CachingEndpointClient(client)
            clients[endpointId] = wrapped
        } else {
            clients.remove(endpointId)
        }
    }

    override suspend fun remove(endpointId: String): Unit = mutex.withLock {
        clients.remove(endpointId)
        EndpointCache.clearForEndpoint(endpointId)
    }

    suspend fun populateEager(entities: List<EndpointEntity>): Unit = mutex.withLock {
        for (entity in entities) {
            if (!clients.containsKey(entity.endpointId)) {
                val client = buildClient(entity) ?: continue
                val wrapped = CachingEndpointClient(client)
                clients[entity.endpointId] = wrapped
            }
        }
    }

    override suspend fun buildProxyClient(proxyId: String?): ProxyClient? =
        proxyId?.let { id -> getOrBuildProxyClient(id) }

    private suspend fun getOrBuildProxyClient(proxyId: String): ProxyClient? {
        proxyClients[proxyId]?.let { return it }
        return runCatching {
            val proxy = proxyDao.getById(proxyId) ?: return@runCatching null
            val proxyFields = json.decodeFromString<Map<String, String>>(proxy.fieldsJson)
            val client = proxyProviderRegistry.proxy(proxy.proxyType).createClient(proxyFields)
            proxyClients[proxyId] = client
            client
        }.getOrNull()
    }

    override suspend fun getAllProxyClients(): Map<String, ProxyClient> = mutex.withLock {
        val allProxies = proxyDao.getAll()
        allProxies.forEach { proxy ->
            if (!proxyClients.containsKey(proxy.id)) {
                getOrBuildProxyClient(proxy.id)
            }
        }
        proxyClients.toMap()
    }

    override suspend fun getProxyClient(proxyId: String): ProxyClient? = mutex.withLock {
        getOrBuildProxyClient(proxyId)
    }

    private suspend fun buildClient(entity: EndpointEntity): EndpointClient? {
        val provider = runCatching { providerRegistry.provider(entity.protocol) }.getOrNull()
            ?: return null
        val values = runCatching {
            json.decodeFromString<Map<String, String>>(entity.fieldsJson)
        }.getOrNull() ?: return null

        val proxyClient: ProxyClient? = buildProxyClient(entity.proxyId)

        val client = runCatching {
            provider.createClient(values)
        }.getOrNull() ?: return null

        client.proxyClient = proxyClient
        client.imageLoader = ImageLoader.Builder(appContext)
            .diskCache(sharedDiskCache)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = proxyClient?.getHttpClient() ?: OkHttpClient())) }
            .build()
        client.endpointId = entity.endpointId
        client.protocol = entity.protocol

        return client
    }
}
