package com.opentune.app.providers

import com.opentune.provider.CodecCapabilities
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader
import java.util.ServiceLoader

class OpenTuneProviderRegistry private constructor(
    private val providersById: MutableMap<String, OpenTuneProvider>,
) {
    @Volatile var codecCapabilities: CodecCapabilities = CodecCapabilities(
        supportedVideoMimeTypes = listOf("video/avc"),
        supportedAudioMimeTypes = listOf("audio/mp4a-latm"),
    )
        private set

    fun setCapabilities(capabilities: CodecCapabilities) {
        this.codecCapabilities = capabilities
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
