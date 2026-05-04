package com.opentune.emby.api

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.provider.CatalogBindingPlugin
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.PlaybackResolveDeps
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.ProviderConfigBackend
import com.opentune.provider.ServerFieldSpec
import java.util.UUID

class EmbyProvider(
    private val deviceProfile: DeviceProfile,
) : OpenTuneProvider {

    override val providerId: String = OpenTuneProviderIds.HTTP_LIBRARY

    private val catalogPluginImpl = EmbyCatalogBindingPlugin(deviceProfile)
    private val configBackendImpl = EmbyConfigBackend(deviceProfile)

    override fun addFields(): List<ServerFieldSpec> = EmbyServerFields.serverAddFields()

    override fun editFields(): List<ServerFieldSpec> = EmbyServerFields.serverEditFields()

    override val catalogPlugin: CatalogBindingPlugin get() = catalogPluginImpl

    override val configBackend: ProviderConfigBackend get() = configBackendImpl

    override suspend fun resolvePlayback(
        deps: PlaybackResolveDeps,
        sourceId: Long,
        itemRef: String,
        startMs: Long,
    ): PlaybackSpec = EmbyPlaybackResolver.resolve(deps, sourceId, itemRef, startMs, deviceProfile)

    override fun bootstrap(context: Context) {
        val appContext = context.applicationContext
        EmbyClientIdentificationStore.install(
            EmbyClientIdentification(
                clientName = "OpenTune",
                deviceName = Build.MODEL.ifBlank { "Android" },
                deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
                clientVersion = try {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
                } catch (_: Exception) {
                    "0"
                },
            ),
        )
    }
}
