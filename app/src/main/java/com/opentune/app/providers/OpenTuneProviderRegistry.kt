package com.opentune.app.providers

import com.opentune.provider.OpenTuneProvider
import java.util.ServiceLoader

class OpenTuneProviderRegistry private constructor(
    private val providersById: Map<String, OpenTuneProvider>,
) {
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
