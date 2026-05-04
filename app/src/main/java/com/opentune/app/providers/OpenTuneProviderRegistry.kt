package com.opentune.app.providers

import com.opentune.provider.OpenTuneProvider

class OpenTuneProviderRegistry private constructor(
    private val providersById: Map<String, OpenTuneProvider>,
) {
    fun provider(providerId: String): OpenTuneProvider =
        providersById[providerId] ?: error("Unknown provider: $providerId")

    fun allProviders(): Collection<OpenTuneProvider> = providersById.values

    companion object {
        private const val EMBY_PROVIDER_CLASS = "com.opentune." + "emby.api.EmbyProvider"
        private const val SMB_PROVIDER_CLASS = "com.opentune." + "smb.SmbProvider"

        fun default(deviceProfile: Any): OpenTuneProviderRegistry {
            val providers = listOf(
                newProvider(EMBY_PROVIDER_CLASS, deviceProfile),
                newProvider(SMB_PROVIDER_CLASS),
            )
            return OpenTuneProviderRegistry(providers.associateBy { it.providerId })
        }

        private fun newProvider(className: String, vararg args: Any): OpenTuneProvider {
            val klass = Class.forName(className)
            val ctor = klass.constructors.firstOrNull { constructor ->
                constructor.parameterCount == args.size &&
                    constructor.parameterTypes.indices.all { idx ->
                        constructor.parameterTypes[idx].isInstance(args[idx])
                    }
            } ?: error("No matching constructor for provider: $className")
            val instance = ctor.newInstance(*args)
            return instance as? OpenTuneProvider
                ?: error("Provider class does not implement OpenTuneProvider: $className")
        }
    }
}
