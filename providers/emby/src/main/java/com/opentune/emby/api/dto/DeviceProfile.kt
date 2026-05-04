package com.opentune.emby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceProfile(
    @SerialName("Name") val name: String = "OpenTune Android TV",
    @SerialName("Id") val id: String? = null,
    @SerialName("Identification") val identification: DeviceIdentification? = null,
    @SerialName("FriendlyName") val friendlyName: String = "OpenTune",
    @SerialName("Manufacturer") val manufacturer: String = "OpenTune",
    @SerialName("ModelName") val modelName: String = "Android TV",
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long = 120_000_000,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Long = 120_000_000,
    @SerialName("MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Int = 192_000,
    @SerialName("MusicSyncBitrate") val musicSyncBitrate: Int = 128_000,
    @SerialName("SupportedMediaTypes") val supportedMediaTypes: String = "Video,Audio",
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile> = emptyList(),
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile> = emptyList(),
    @SerialName("CodecProfiles") val codecProfiles: List<CodecProfile> = emptyList(),
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile> = emptyList(),
    @SerialName("ResponseProfiles") val responseProfiles: List<ResponseProfile> = emptyList(),
)

@Serializable
data class DeviceIdentification(
    @SerialName("FriendlyName") val friendlyName: String = "OpenTune",
    @SerialName("Manufacturer") val manufacturer: String = "OpenTune",
    @SerialName("ModelName") val modelName: String = "Android TV",
    @SerialName("DeviceDescription") val deviceDescription: String = "OpenTune for Android TV",
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String = "h264",
    @SerialName("AudioCodec") val audioCodec: String = "aac",
    @SerialName("Protocol") val protocol: String = "hls",
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("MaxAudioChannels") val maxAudioChannels: String = "8",
    @SerialName("MinSegments") val minSegments: Int = 1,
    @SerialName("SegmentLength") val segmentLength: Int = 6,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = false,
)

@Serializable
data class CodecProfile(
    @SerialName("Type") val type: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
)

@Serializable
data class ProfileCondition(
    @SerialName("Condition") val condition: String,
    @SerialName("Property") val property: String,
    @SerialName("Value") val value: String,
    @SerialName("IsRequired") val isRequired: Boolean = false,
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String = "Embed",
)

@Serializable
data class ResponseProfile(
    @SerialName("Type") val type: String = "Video",
    @SerialName("Container") val container: String = "m3u8",
    @SerialName("MimeType") val mimeType: String = "application/vnd.apple.mpegurl",
)
