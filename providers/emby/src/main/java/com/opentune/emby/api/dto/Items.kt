package com.opentune.emby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueryResultBaseItemDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("UserData") val userData: UserItemDataDto? = null,
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Studios") val studios: List<StudioDto>? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("Etag") val etag: String? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String>? = null,
    @SerialName("ExternalUrls") val externalUrls: List<ExternalUrlDto>? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo>? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null,
)

@Serializable
data class UserItemDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
    @SerialName("Played") val played: Boolean? = null,
)

@Serializable
data class StudioDto(
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class ExternalUrlDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Url") val url: String? = null,
)
