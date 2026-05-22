package com.opentune.proxy.contract

object ProxyProviderRegistryHolder {
    @Volatile private var instance: ProxyProviderRegistry? = null
    fun set(registry: ProxyProviderRegistry) { instance = registry }
    fun get(): ProxyProviderRegistry = requireNotNull(instance) { "ProxyProviderRegistry not initialized" }
}
