package com.opentune.content.contract

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

data class PlatformCapabilities(
    val videoMime: List<String>,
    val audioMime: List<String>,
    val maxPixels: Int = 1920 * 1080,
    val subtitleFormats: List<String> = listOf("srt", "ass", "ssa", "vtt"),
)
