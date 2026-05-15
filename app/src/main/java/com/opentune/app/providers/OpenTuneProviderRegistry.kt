package com.opentune.app.providers

import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

class OpenTuneProviderRegistry {
    private val providersById = ConcurrentHashMap<String, OpenTuneProvider>()

    private val _providersFlow = MutableStateFlow<List<OpenTuneProvider>>(emptyList())

    /** Emits the current list of registered providers, growing as discovery completes. */
    val providersFlow: StateFlow<List<OpenTuneProvider>> = _providersFlow.asStateFlow()

    @Volatile var platformCapabilities: PlatformCapabilities = PlatformCapabilities(
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

    fun provider(protocol: String): OpenTuneProvider =
        providersById[protocol] ?: error("Unknown provider: $protocol")

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
