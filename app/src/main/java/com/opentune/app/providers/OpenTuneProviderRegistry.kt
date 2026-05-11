package com.opentune.app.providers

import com.opentune.provider.CodecCapabilities
import com.opentune.provider.OpenTuneProvider
import java.util.ServiceLoader

class OpenTuneProviderRegistry private constructor(
    private val providersById: Map<String, OpenTuneProvider>,
) {
    @Volatile var codecCapabilities: CodecCapabilities = CodecCapabilities(
        supportedVideoMimeTypes = listOf("video/avc"),
        supportedAudioMimeTypes = listOf("audio/mp4a-latm"),
    )
        private set

    fun setCapabilities(capabilities: CodecCapabilities) {
        this.codecCapabilities = capabilities
    }

    fun provider(providerType: String): OpenTuneProvider =
        providersById[providerType] ?: error("Unknown provider: $providerType")

    fun allProviders(): Collection<OpenTuneProvider> = providersById.values

    companion object {
        fun discover(): OpenTuneProviderRegistry {
            val providers = ServiceLoader
                .load(OpenTuneProvider::class.java, OpenTuneProvider::class.java.classLoader)
                .toList()
            return OpenTuneProviderRegistry(providers.associateBy { it.providerType })
        }
    }
}
