package com.opentune.content.contract

import com.opentune.storage.EndpointEntity
import okhttp3.OkHttpClient

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
    suspend fun buildHttpClient(proxyId: String?): OkHttpClient
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
