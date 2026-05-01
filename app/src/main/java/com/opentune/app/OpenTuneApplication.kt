package com.opentune.app

import android.app.Application
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.deviceprofile.AndroidDeviceProfileBuilder
import com.opentune.storage.OpenTuneDatabase

class OpenTuneApplication : Application() {

    lateinit var database: OpenTuneDatabase
        private set

    val deviceProfile: DeviceProfile by lazy { AndroidDeviceProfileBuilder.build() }

    override fun onCreate() {
        super.onCreate()
        database = OpenTuneDatabase.create(this)
    }
}
