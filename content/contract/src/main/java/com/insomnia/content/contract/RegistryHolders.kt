package com.insomnia.content.contract

import com.insomnia.content.epcache.CachingEndpointClient
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.storage.EndpointEntity

object EndpointClientRegistryHolder {
    @Volatile private var instance: EndpointClientAccess? = null
    fun set(registry: EndpointClientAccess) { instance = registry }
    fun get(): EndpointClientAccess = requireNotNull(instance) { "EndpointClientRegistry not initialized" }
}

interface EndpointClientAccess {
    suspend fun getOrCreate(endpointId: String): CachingEndpointClient?
    suspend fun registerHandle(endpointId: String, entity: EndpointEntity): CachingEndpointClient?
    suspend fun update(endpointId: String, entity: EndpointEntity)
    suspend fun remove(endpointId: String)
    suspend fun buildProxyClient(proxyId: String?): ProxyClient?
    suspend fun getProxyClient(proxyId: String): ProxyClient?
    suspend fun getAllProxyClients(): Map<String, ProxyClient>
}

object InsomniaProviderRegistryHolder {
    @Volatile private var instance: InsomniaProviderAccess? = null
    fun set(registry: InsomniaProviderAccess) { instance = registry }
    fun get(): InsomniaProviderAccess = requireNotNull(instance) { "InsomniaProviderRegistry not initialized" }
}

interface InsomniaProviderAccess {
    fun provider(protocol: String): InsomniaProvider
    fun allProviders(): List<InsomniaProvider>
}
