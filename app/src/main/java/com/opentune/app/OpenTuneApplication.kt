package com.opentune.app

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.opentune.deviceprofile.AndroidDeviceProfileBuilder
import com.opentune.app.drafts.AddServerDraftStore
import com.opentune.app.providers.OpenTuneProviderRegistry
import com.opentune.emby.api.EmbyClientIdentification
import com.opentune.emby.api.EmbyClientIdentificationStore
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.storage.OpenTuneDatabase
import java.util.UUID

class OpenTuneApplication : Application() {

    lateinit var database: OpenTuneDatabase
        private set

    lateinit var addServerDraftStore: AddServerDraftStore
        private set

    lateinit var providerRegistry: OpenTuneProviderRegistry
        private set

    val deviceProfile: DeviceProfile by lazy { AndroidDeviceProfileBuilder.build() }

    override fun onCreate() {
        super.onCreate()
        EmbyClientIdentificationStore.install(
            EmbyClientIdentification(
                clientName = "OpenTune",
                deviceName = Build.MODEL.ifBlank { "Android" },
                deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
                clientVersion = BuildConfig.VERSION_NAME,
            ),
        )
        database = OpenTuneDatabase.create(this)
        addServerDraftStore = AddServerDraftStore(this)
        providerRegistry = OpenTuneProviderRegistry.create()
    }
}
