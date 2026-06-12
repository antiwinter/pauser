package com.opentune.content.contract

import com.opentune.proxy.contract.ProxyClient
import com.opentune.storage.EndpointEntity

object EndpointClientRegistryHolder {
    @Volatile private var instance: EndpointClientAccess? = null
    fun set(registry: EndpointClientAccess) { instance = registry }
    fun get(): EndpointClientAccess = requireNotNull(instance) { "EndpointClientRegistry not initialized" }
}

interface EndpointClientAccess {
    suspend fun getOrCreate(endpointId: String): EndpointClient?
    suspend fun registerHandle(endpointId: String, entity: EndpointEntity): EndpointClient?
    suspend fun update(endpointId: String, entity: EndpointEntity)
    suspend fun remove(endpointId: String)
    suspend fun buildProxyClient(proxyId: String?): ProxyClient?
    suspend fun getProxyClient(proxyId: String): ProxyClient?
    suspend fun getAllProxyClients(): Map<String, ProxyClient>
}

object OpenTuneProviderRegistryHolder {
    @Volatile private var instance: OpenTuneProviderAccess? = null
    fun set(registry: OpenTuneProviderAccess) { instance = registry }
    fun get(): OpenTuneProviderAccess = requireNotNull(instance) { "OpenTuneProviderRegistry not initialized" }
}

interface OpenTuneProviderAccess {
    fun provider(protocol: String): OpenTuneProvider
    fun allProviders(): List<OpenTuneProvider>
}
