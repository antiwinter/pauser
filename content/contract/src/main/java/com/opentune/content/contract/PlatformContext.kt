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

data class ProfileLevel(
    val profile: String,
    val level: Int,
)

data class VideoCodecInfo(
    val codec: String,
    val mime: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val profileLevels: List<ProfileLevel>,
)

data class AudioCodecInfo(
    val codec: String,
    val mime: String,
)

data class PlatformCapabilities(
    val videoCodecs: List<VideoCodecInfo>,
    val audioCodecs: List<AudioCodecInfo>,
    val subtitleFormats: List<String> = listOf("srt", "ass", "ssa", "vtt"),
)
