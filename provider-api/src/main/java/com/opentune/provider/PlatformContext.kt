package com.opentune.provider

interface PlatformContext {
    val deviceName: String
    val deviceId: String
    val clientVersion: String
}
