package com.opentune.emby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackInfoRequest(
    @SerialName("Id") val id: String? = null,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long? = null,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile? = null,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = true,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("IsRemote") val isRemote: Boolean? = null,
    @SerialName("ETag") val eTag: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean? = null,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean? = null,
    @SerialName("IsInfiniteStream") val isInfiniteStream: Boolean? = null,
    @SerialName("RequiresOpening") val requiresOpening: Boolean? = null,
    @SerialName("RequiresClosing") val requiresClosing: Boolean? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("TranscodingSubProtocol") val transcodingSubProtocol: String? = null,
    @SerialName("TranscodingContainer") val transcodingContainer: String? = null,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("AddApiKeyToDirectStreamUrl") val addApiKeyToDirectStreamUrl: Boolean? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
)

@Serializable
data class MediaStream(
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsInterlaced") val isInterlaced: Boolean? = null,
    @SerialName("IsDefault") val isDefault: Boolean? = null,
    @SerialName("IsForced") val isForced: Boolean? = null,
    @SerialName("IsExternal") val isExternal: Boolean? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("SampleRate") val sampleRate: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("AverageFrameRate") val averageFrameRate: Float? = null,
    @SerialName("Level") val level: Double? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("AspectRatio") val aspectRatio: String? = null,
)
