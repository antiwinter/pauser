package com.opentune.provider

data class CodecCapabilities(
    val supportedVideoMimeTypes: List<String>,
    val supportedAudioMimeTypes: List<String>,
    val maxVideoPixels: Int = 1920 * 1080,
)
