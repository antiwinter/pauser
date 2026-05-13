package com.opentune.provider

import java.io.File

interface PlatformContext {
    val deviceName: String
    val deviceId: String
    val clientVersion: String
    val cacheDir: File
}
