package com.insomnia.content.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

class InsomniaProviderRegistry : InsomniaProviderAccess {
    private val providersById = ConcurrentHashMap<String, InsomniaProvider>()

    private val _providersFlow = MutableStateFlow<List<InsomniaProvider>>(emptyList())

    val providersFlow: StateFlow<List<InsomniaProvider>> = _providersFlow.asStateFlow()

    fun register(provider: InsomniaProvider) {
        providersById[provider.protocol] = provider
        _providersFlow.update { it + provider }
    }

    override fun provider(protocol: String): InsomniaProvider =
        providersById[protocol] ?: error("Unknown provider: $protocol")

    override fun allProviders(): List<InsomniaProvider> = _providersFlow.value

    suspend fun discoverAsync() = coroutineScope {
        ServiceLoader
            .load(InsomniaProviderLoader::class.java, InsomniaProviderLoader::class.java.classLoader)
            .forEach { loader -> launch(Dispatchers.IO) { loader.load(::register) } }
    }
}
