package com.opentune.app

import android.app.Application
import com.opentune.app.drafts.AddServerDraftStore
import com.opentune.app.providers.OpenTuneProviderRegistry
import com.opentune.deviceprofile.AndroidDeviceProfileBuilder
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings

class OpenTuneApplication : Application() {

    lateinit var database: OpenTuneDatabase
        private set

    lateinit var storageBindings: OpenTuneStorageBindings
        private set

    lateinit var addServerDraftStore: AddServerDraftStore
        private set

    lateinit var providerRegistry: OpenTuneProviderRegistry
        private set

    val deviceProfile: DeviceProfile by lazy { AndroidDeviceProfileBuilder.build() }

    override fun onCreate() {
        super.onCreate()
        database = OpenTuneDatabase.create(this)
        storageBindings = OpenTuneStorageBindings.create(database)
        addServerDraftStore = AddServerDraftStore(this)
        providerRegistry = OpenTuneProviderRegistry.default(deviceProfile)
        providerRegistry.allProviders().forEach { it.bootstrap(this) }
    }
}
