package com.opentune.app.providers

import com.opentune.provider.OpenTuneProvider
import com.opentune.smb.SmbProvider
import java.io.File

class OpenTuneProviderRegistry private constructor(
    private val providersById: Map<String, OpenTuneProvider>,
) {
    fun provider(providerType: String): OpenTuneProvider =
        providersById[providerType] ?: error("Unknown provider: $providerType")

    fun allProviders(): Collection<OpenTuneProvider> = providersById.values

    companion object {
        private const val EMBY_PROVIDER_CLASS = "com.opentune." + "emby.api.EmbyProvider"

        fun default(smbSubtitleCacheDir: File): OpenTuneProviderRegistry {
            val providers = listOf(
                newProvider(EMBY_PROVIDER_CLASS),
                SmbProvider(smbSubtitleCacheDir),
            )
            return OpenTuneProviderRegistry(providers.associateBy { it.providerType })
        }

        private fun newProvider(className: String): OpenTuneProvider {
            val klass = Class.forName(className)
            val ctor = klass.constructors.firstOrNull { it.parameterCount == 0 }
                ?: error("No no-arg constructor for provider: $className")
            return ctor.newInstance() as? OpenTuneProvider
                ?: error("Provider class does not implement OpenTuneProvider: $className")
        }
    }
}
