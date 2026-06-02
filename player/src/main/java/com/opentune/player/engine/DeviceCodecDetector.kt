package com.opentune.player.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.opentune.content.contract.AudioCodecInfo
import com.opentune.content.contract.PlatformCapabilities
import com.opentune.content.contract.ProfileLevel
import com.opentune.content.contract.VideoCodecInfo

/**
 * Lazy-cached device codec capabilities probe.
 * Reports standard profile levels (e.g., 52 for 5.2, 41 for 4.1) — Android's
 * raw bit-flag constants are mapped at the source so callers get clean values.
 */
@UnstableApi
object DeviceCodecDetector {

    @Volatile private var cache: PlatformCapabilities? = null

    @Synchronized
    fun detect(): PlatformCapabilities {
        cache?.let { return it }
        val result = doDetect()
        cache = result
        return result
    }

    private fun doDetect(): PlatformCapabilities {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
        } else {
            throw IllegalStateException("DeviceCodecDetector requires API 21+")
        }

        val videoMimes = mutableSetOf<String>()
        val audioMimes = mutableSetOf<String>()
        val videoCodecsMap = mutableMapOf<String, MutableSet<ProfileLevel>>()
        val videoMaxWidth = mutableMapOf<String, Int>()
        val videoMaxHeight = mutableMapOf<String, Int>()

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val caps = info.getCapabilitiesForType(mime)
                when {
                    mime.startsWith("video/") -> {
                        videoMimes += mime
                        val codec = mimeToCodec(mime) ?: continue
                        val vc = caps.videoCapabilities ?: continue
                        videoMaxWidth.merge(codec, vc.supportedWidths.upper) { a, b -> if (a > b) a else b }
                        videoMaxHeight.merge(codec, vc.supportedHeights.upper) { a, b -> if (a > b) a else b }

                        val levelSet = videoCodecsMap.getOrPut(codec) { mutableSetOf() }
                        for (pl in caps.profileLevels) {
                            val profileName = profileNameFor(mime, pl.profile)
                            if (profileName != null) {
                                val standardLevel = androidLevelToStandard(mime, pl.level)
                                levelSet += ProfileLevel(profileName, standardLevel)
                            }
                        }
                    }
                    mime.startsWith("audio/") -> {
                        audioMimes += mime
                    }
                }
            }
        }

        val codecToMime = videoMimes.mapNotNull { mime ->
            val codec = mimeToCodec(mime) ?: return@mapNotNull null
            codec to mime
        }.toMap()

        val videoCodecs = codecToMime.map { (codec, mime) ->
            VideoCodecInfo(
                codec = codec,
                mime = mime,
                maxWidth = videoMaxWidth[codec] ?: 0,
                maxHeight = videoMaxHeight[codec] ?: 0,
                profileLevels = videoCodecsMap[codec]?.toList() ?: emptyList(),
            )
        }

        val audioCodecToMime = audioMimes.mapNotNull { mime ->
            val codec = mimeToAudioCodec(mime) ?: return@mapNotNull null
            codec to mime
        }.toMap()

        val audioCodecs = audioCodecToMime.map { (codec, mime) ->
            AudioCodecInfo(codec = codec, mime = mime)
        }

        return PlatformCapabilities(
            videoCodecs = videoCodecs,
            audioCodecs = audioCodecs,
            subtitleFormats = listOf("srt", "ass", "ssa", "vtt", "webvtt"),
        )
    }

    private fun mimeToCodec(mime: String): String? = when (mime) {
        "video/avc" -> "h264"
        "video/hevc" -> "hevc"
        "video/vp9" -> "vp9"
        "video/av01" -> "av1"
        else -> null
    }

    private fun mimeToAudioCodec(mime: String): String? = when (mime) {
        "audio/mp4a-latm" -> "aac"
        "audio/ac3" -> "ac3"
        "audio/eac3" -> "eac3"
        "audio/mpeg" -> "mp3"
        "audio/opus" -> "opus"
        "audio/flac" -> "flac"
        else -> null
    }

    private fun profileNameFor(mime: String, profile: Int): String? = when (mime) {
        "video/hevc" -> hevcProfileName(profile)
        "video/avc" -> h264ProfileName(profile)
        "video/vp9" -> vp9ProfileName(profile)
        "video/av01" -> av1ProfileName(profile)
        else -> null
    }

    private fun hevcProfileName(profile: Int): String? = when (profile) {
        1 -> "main"
        2 -> "main10"
        else -> null
    }

    private fun h264ProfileName(profile: Int): String? = when (profile) {
        1 -> "baseline"
        2 -> "main"
        8 -> "high"
        else -> null
    }

    private fun vp9ProfileName(profile: Int): String? = when (profile) {
        0 -> "profile0"
        1 -> "profile2"
        else -> null
    }

    private fun av1ProfileName(profile: Int): String? = when (profile) {
        1 -> "main"
        2 -> "high"
        4 -> "professional"
        else -> null
    }

    // ── Android raw → standard level mapping ────────────────────────────────

    private val H264_LEVELS = mapOf(
        0x1L     to 10,   0x2L     to 11,  0x4L     to 11,  0x8L     to 12,
        0x10L    to 13,   0x20L    to 20,  0x40L    to 21,  0x80L    to 22,
        0x100L   to 30,   0x200L   to 31,  0x400L   to 32,  0x800L   to 40,
        0x1000L  to 41,   0x2000L  to 42,  0x4000L  to 50,  0x8000L  to 51,
        0x10000L to 52,   0x20000L to 60,  0x40000L to 61,  0x80000L to 62,
    )

    private val HEVC_LEVELS = mapOf(
        0x1L    to 10,  0x2L    to 20,  0x4L    to 21,
        0x8L    to 30,  0x10L   to 31,  0x20L   to 40,  0x40L   to 41,
        0x80L   to 50,  0x100L  to 51,  0x200L  to 52,  0x400L  to 60,
        0x800L  to 61,  0x1000L to 62,
    )

    private val VP9_LEVELS = mapOf(
        0x1L    to 10,  0x2L    to 11,  0x4L    to 20,   0x8L    to 21,
        0x10L   to 30,  0x20L   to 31,  0x40L   to 40,   0x80L   to 41,
        0x100L  to 50,  0x200L  to 51,  0x400L  to 52,
    )

    private val AV1_LEVELS = mapOf(
        0x1L    to 20,  0x2L    to 21,  0x4L    to 22,   0x8L    to 23,
        0x10L   to 30,  0x20L   to 31,  0x40L   to 32,   0x80L   to 33,
        0x100L  to 40,  0x200L  to 41,  0x400L  to 42,   0x800L  to 43,
    )

    private fun androidLevelToStandard(mime: String, rawLevel: Int): Int {
        // Already in standard range, pass through.
        if (rawLevel in 1..99) return rawLevel
        val map = when (mime) {
            "video/avc"  -> H264_LEVELS
            "video/hevc" -> HEVC_LEVELS
            "video/vp9"  -> VP9_LEVELS
            "video/av01" -> AV1_LEVELS
            else         -> emptyMap()
        }
        return map[rawLevel.toLong()] ?: rawLevel
    }
}
