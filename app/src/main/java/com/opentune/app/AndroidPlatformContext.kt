package com.opentune.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.opentune.provider.PlatformContext
import java.util.UUID

class AndroidPlatformContext(private val androidContext: Context) : PlatformContext {
    override val deviceName: String
        get() = Build.MODEL.ifBlank { "Android" }

    override val deviceId: String
        get() = Settings.Secure.getString(androidContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

    override val clientVersion: String
        get() = try {
            @Suppress("DEPRECATION")
            androidContext.packageManager.getPackageInfo(androidContext.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
}
