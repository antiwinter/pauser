package com.opentune.content.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

class OpenTuneProviderRegistry : OpenTuneProviderAccess {
    private val providersById = ConcurrentHashMap<String, OpenTuneProvider>()

    private val _providersFlow = MutableStateFlow<List<OpenTuneProvider>>(emptyList())

    val providersFlow: StateFlow<List<OpenTuneProvider>> = _providersFlow.asStateFlow()

    fun register(provider: OpenTuneProvider) {
        providersById[provider.protocol] = provider
        _providersFlow.update { it + provider }
    }

    override fun provider(protocol: String): OpenTuneProvider =
        providersById[protocol] ?: error("Unknown provider: $protocol")

    override fun allProviders(): List<OpenTuneProvider> = _providersFlow.value

    suspend fun discoverAsync() = coroutineScope {
        ServiceLoader
            .load(OpenTuneProviderLoader::class.java, OpenTuneProviderLoader::class.java.classLoader)
            .forEach { loader -> launch(Dispatchers.IO) { loader.load(::register) } }
    }
}
