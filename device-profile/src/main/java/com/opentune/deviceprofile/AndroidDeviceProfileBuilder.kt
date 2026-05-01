package com.opentune.deviceprofile

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.opentune.emby.api.dto.CodecProfile
import com.opentune.emby.api.dto.DeviceIdentification
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.dto.DirectPlayProfile
import com.opentune.emby.api.dto.ProfileCondition
import com.opentune.emby.api.dto.ResponseProfile
import com.opentune.emby.api.dto.SubtitleProfile
import com.opentune.emby.api.dto.TranscodingProfile

/**
 * Builds an Emby [DeviceProfile] from [MediaCodecList] capabilities (hardware/software decoders).
 * Omits optional FFmpeg-style decoders; server may transcode unsupported formats.
 */
object AndroidDeviceProfileBuilder {

    private val videoMimeTypes = listOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaFormat.MIMETYPE_VIDEO_AV1,
    )

    private val audioMimeTypes = listOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_AC3,
        MediaFormat.MIMETYPE_AUDIO_EAC3,
        MediaFormat.MIMETYPE_AUDIO_MPEG,
        MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_FLAC,
    )

    fun build(): DeviceProfile {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val supportedVideo = videoMimeTypes.filter { isDecoderSupported(list, it) }
        val supportedAudio = audioMimeTypes.filter { isDecoderSupported(list, it) }

        val videoCodecCsv = supportedVideo.map { mimeToEmbyVideoCodec(it) }.distinct().joinToString(",")
        val audioCodecCsv = supportedAudio.map { mimeToEmbyAudioCodec(it) }.distinct().joinToString(",")

        val directPlay = buildDirectPlayProfiles(videoCodecCsv, audioCodecCsv)

        val maxPixels = maxOf(
            maxVideoPixels(list, MediaFormat.MIMETYPE_VIDEO_AVC),
            maxVideoPixels(list, MediaFormat.MIMETYPE_VIDEO_HEVC),
        ).coerceAtLeast(1920 * 1080)

        val maxWidth = sqrtApprox(maxPixels, 16) * 16
        val maxHeight = maxPixels / maxWidth

        val videoConditions = buildList {
            add(
                ProfileCondition(
                    condition = "LessThanEqual",
                    property = "VideoBitrate",
                    value = "120000000",
                    isRequired = false,
                ),
            )
            add(
                ProfileCondition(
                    condition = "LessThanEqual",
                    property = "Width",
                    value = maxWidth.toString(),
                    isRequired = false,
                ),
            )
            add(
                ProfileCondition(
                    condition = "LessThanEqual",
                    property = "Height",
                    value = maxHeight.coerceAtLeast(1080).toString(),
                    isRequired = false,
                ),
            )
        }

        val codecProfiles = buildList {
            if (videoCodecCsv.isNotEmpty()) {
                add(
                    CodecProfile(
                        type = "Video",
                        codec = videoCodecCsv,
                        conditions = videoConditions,
                    ),
                )
            }
            if (audioCodecCsv.isNotEmpty()) {
                add(
                    CodecProfile(
                        type = "Audio",
                        codec = audioCodecCsv,
                        conditions = emptyList(),
                    ),
                )
            }
        }

        val identification = DeviceIdentification(
            friendlyName = "OpenTune",
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            deviceDescription = "OpenTune on ${Build.MODEL}",
        )

        return DeviceProfile(
            name = "OpenTune Android TV",
            identification = identification,
            friendlyName = "OpenTune",
            manufacturer = "OpenTune",
            modelName = Build.MODEL,
            maxStreamingBitrate = 120_000_000,
            maxStaticBitrate = 120_000_000,
            directPlayProfiles = directPlay,
            transcodingProfiles = defaultTranscodingProfiles(),
            codecProfiles = codecProfiles,
            subtitleProfiles = defaultSubtitleProfiles(),
            responseProfiles = defaultResponseProfiles(),
        )
    }

    private fun sqrtApprox(n: Int, align: Int): Int {
        var w = kotlin.math.sqrt(n.toDouble()).toInt() / align * align
        if (w < 1) w = 1920
        return w
    }

    private fun isDecoderSupported(list: MediaCodecList, mime: String): Boolean {
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun maxVideoPixels(list: MediaCodecList, mime: String): Int {
        var max = 0
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes.filter { it.equals(mime, ignoreCase = true) }
            if (types.isEmpty()) continue
            try {
                val caps = info.getCapabilitiesForType(types.first())
                val vd = caps.videoCapabilities ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    max = maxOf(max, vd.supportedWidths.upper * vd.supportedHeights.upper)
                }
            } catch (_: IllegalArgumentException) {
            }
        }
        return max
    }

    private fun buildDirectPlayProfiles(videoCodecs: String, audioCodecs: String): List<DirectPlayProfile> {
        val v = if (videoCodecs.isBlank()) "h264" else videoCodecs
        val a = if (audioCodecs.isBlank()) "aac" else audioCodecs
        return listOf(
            DirectPlayProfile(container = "mp4,mkv,avi,m4v,mov,webm", type = "Video", videoCodec = v, audioCodec = a),
        )
    }

    private fun defaultTranscodingProfiles(): List<TranscodingProfile> = listOf(
        TranscodingProfile(
            container = "ts",
            type = "Video",
            videoCodec = "h264",
            audioCodec = "aac",
            protocol = "hls",
            context = "Streaming",
        ),
    )

    private fun defaultSubtitleProfiles(): List<SubtitleProfile> = listOf(
        SubtitleProfile(format = "srt"),
        SubtitleProfile(format = "vtt"),
        SubtitleProfile(format = "ass"),
    )

    private fun defaultResponseProfiles(): List<ResponseProfile> = listOf(
        ResponseProfile(type = "Video", container = "m3u8", mimeType = "application/vnd.apple.mpegurl"),
    )

    private fun mimeToEmbyVideoCodec(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
        else -> "h264"
    }

    private fun mimeToEmbyAudioCodec(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_AUDIO_AAC -> "aac"
        MediaFormat.MIMETYPE_AUDIO_AC3 -> "ac3"
        MediaFormat.MIMETYPE_AUDIO_EAC3 -> "eac3"
        MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "opus"
        MediaFormat.MIMETYPE_AUDIO_FLAC -> "flac"
        else -> "aac"
    }
}
