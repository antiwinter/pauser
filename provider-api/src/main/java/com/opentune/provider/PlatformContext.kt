package com.opentune.provider

import java.io.File

interface PlatformInfo {
    val deviceName: String
    val deviceId: String
    val clientVersion: String
    val cacheDir: File
}

object PlatformInfoHolder {
    @Volatile private var _info: PlatformInfo? = null
    fun set(info: PlatformInfo) { _info = info }
    fun get(): PlatformInfo = _info ?: error("PlatformInfo not initialized")
}
