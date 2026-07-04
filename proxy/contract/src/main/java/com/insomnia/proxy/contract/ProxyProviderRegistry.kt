package com.insomnia.proxy.contract

import java.util.ServiceLoader

class ProxyProviderRegistry private constructor(
    private val providers: Map<String, ProxyProvider>,
) {
    fun proxy(proxyType: String): ProxyProvider =
        providers[proxyType] ?: error("Unknown proxy provider: $proxyType")

    fun allProxies(): Collection<ProxyProvider> = providers.values

    companion object {
        fun discover(): ProxyProviderRegistry {
            val list = ServiceLoader
                .load(ProxyProvider::class.java, ProxyProvider::class.java.classLoader)
                .toList()
            return ProxyProviderRegistry(list.associateBy { it.proxyType })
        }
    }
}
