package com.opentune.app

import android.app.Application
import com.opentune.app.providers.OpenTuneProviderRegistry
import com.opentune.app.providers.ProviderInstanceRegistry
import com.opentune.deviceprofile.AndroidDeviceProfileBuilder
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings

class OpenTuneApplication : Application() {

    lateinit var database: OpenTuneDatabase
        private set

    lateinit var storageBindings: OpenTuneStorageBindings
        private set

    lateinit var providerRegistry: OpenTuneProviderRegistry
        private set

    lateinit var instanceRegistry: ProviderInstanceRegistry
        private set

    val deviceProfile by lazy { AndroidDeviceProfileBuilder.build() }

    override fun onCreate() {
        super.onCreate()
        database = OpenTuneDatabase.create(this)
        storageBindings = OpenTuneStorageBindings.create(database, this)
        providerRegistry = OpenTuneProviderRegistry.default(deviceProfile)
        instanceRegistry = ProviderInstanceRegistry(
            serverDao = storageBindings.serverDao,
            providerRegistry = providerRegistry,
        )
        providerRegistry.allProviders().forEach { it.bootstrap(this) }
    }
}
