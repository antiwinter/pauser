package com.opentune.provider

data class PlatformCapabilities(
    val videoMime: List<String>,
    val audioMime: List<String>,
    val maxPixels: Int = 1920 * 1080,
    val subtitleFormats: List<String> = listOf("srt", "ass", "ssa", "vtt"),
)
