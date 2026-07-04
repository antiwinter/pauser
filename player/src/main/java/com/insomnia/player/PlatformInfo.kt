package com.insomnia.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable

/**
 * Lazy-cached device info probe.
 * Reports standard profile levels (e.g., 52 for 5.2, 41 for 4.1) — Android's
 * raw bit-flag constants are mapped at the source so callers get clean values.
 */
object PlatformInfo {

    @Volatile private var cache: PlatformInfoData? = null

    fun detect(context: Context): PlatformInfoData {
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            val result = doDetect(context.applicationContext)
            cache = result
            result
        }
    }

    private fun doDetect(context: Context): PlatformInfoData {
        val deviceName = Build.MODEL.ifBlank { "Android" }
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val clientVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)

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

        return PlatformInfoData(
            deviceName = deviceName,
            deviceId = deviceId,
            clientVersion = clientVersion,
            videoCodecs = videoCodecs,
            audioCodecs = audioCodecs,
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

@Serializable
data class ProfileLevel(
    val profile: String,
    val level: Int,
)

@Serializable
data class VideoCodecInfo(
    val codec: String,
    val mime: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val profileLevels: List<ProfileLevel>,
)

@Serializable
data class AudioCodecInfo(
    val codec: String,
    val mime: String,
)

@Serializable
data class PlatformInfoData(
    val deviceName: String,
    val deviceId: String,
    val clientVersion: String,
    val videoCodecs: List<VideoCodecInfo>,
    val audioCodecs: List<AudioCodecInfo>,
    // Single source of truth for the player's subtitle capability profile, sent to Emby as
    // SubtitleProfile (Method=Embed). Matches the parsers media3-extractor 1.5.0 actually ships:
    // subrip→srt, ssa→ass/ssa, webvtt→vtt/webvtt, ttml, tx3g, pgs→pgssub, dvb→dvbsub. Formats
    // media3 has no parser for (dvd_subtitle, xsub, microdvd) are intentionally absent — the
    // provider transcodes those to ass via Stream.ass.
    val subtitleFormats: List<String> = listOf(
        "srt", "ass", "ssa", "vtt", "webvtt", "ttml", "tx3g", "pgssub", "dvbsub",
    ),
)
