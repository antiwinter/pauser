package com.opentune.app.providers

import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader
import java.util.ServiceLoader

class OpenTuneProviderRegistry private constructor(
    private val providersById: MutableMap<String, OpenTuneProvider>,
) {
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
    }

    fun provider(protocol: String): OpenTuneProvider =
        providersById[protocol] ?: error("Unknown provider: $protocol")

    fun allProviders(): Collection<OpenTuneProvider> = providersById.values

    companion object {
        fun discover(): OpenTuneProviderRegistry {
            val registry = OpenTuneProviderRegistry(mutableMapOf())
            ServiceLoader
                .load(OpenTuneProviderLoader::class.java, OpenTuneProviderLoader::class.java.classLoader)
                .forEach { loader -> loader.load { registry.register(it) } }
            return registry
        }
    }
}
