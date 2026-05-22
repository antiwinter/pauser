package com.opentune.app.providers

import com.opentune.content.contract.PlatformCapabilities
import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.OpenTuneProviderAccess
import com.opentune.content.contract.OpenTuneProviderLoader
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

    /** Emits the current list of registered providers, growing as discovery completes. */
    val providersFlow: StateFlow<List<OpenTuneProvider>> = _providersFlow.asStateFlow()

    @Volatile override var platformCapabilities: PlatformCapabilities = PlatformCapabilities(
        videoMime = listOf("video/avc"),
        audioMime = listOf("audio/mp4a-latm"),
    )
        private set

    fun setCapabilities(capabilities: PlatformCapabilities) {
        this.platformCapabilities = capabilities
    }

    fun register(provider: OpenTuneProvider) {
        providersById[provider.protocol] = provider
        _providersFlow.update { it + provider }
    }

    override fun provider(protocol: String): OpenTuneProvider =
        providersById[protocol] ?: error("Unknown provider: $protocol")

    override fun allProviders(): List<OpenTuneProvider> = _providersFlow.value

    /**
     * Discovers all [OpenTuneProviderLoader]s via [ServiceLoader] and runs them in parallel
     * on [Dispatchers.IO]. Each loader calls [register] as soon as its provider is ready.
     * Suspends until all loaders have completed.
     */
    suspend fun discoverAsync() = coroutineScope {
        ServiceLoader
            .load(OpenTuneProviderLoader::class.java, OpenTuneProviderLoader::class.java.classLoader)
            .forEach { loader -> launch(Dispatchers.IO) { loader.load(::register) } }
    }
}
