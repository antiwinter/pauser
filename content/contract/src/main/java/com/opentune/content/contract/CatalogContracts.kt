package com.opentune.content.contract

import kotlinx.serialization.Serializable

@Serializable
enum class EntryType {
    Folder,
    Playable,
    Other,
    Series,
    Season,
    Episode,
    Image,
    Digipak,
}

@Serializable
data class EntryUserData(
    val positionMs: Long,
    val isFavorite: Boolean,
    val played: Boolean,
)

@Serializable
data class EntryInfo(
    val id: String,
    val title: String,
    val type: EntryType,
    val cover: String? = null,
    val userData: EntryUserData? = null,
    val originalTitle: String? = null,
    val genres: List<String>? = null,
    val communityRating: Float? = null,
    val studios: List<String>? = null,
    val etag: String? = null,
    val indexNumber: Int? = null,
    val overview: String? = null,
    val childCount: Int? = null,
    val parentId: String? = null,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
)

data class EntryList(
    val items: List<EntryInfo>,
    val totalCount: Int,
)

data class ExternalUrl(
    val name: String,
    val url: String,
)

data class StreamInfo(
    val index: Int,
    val type: String,
    val codec: String?,
    val title: String?,
    val language: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
)

data class EntryDetail(
    val title: String,
    val overview: String?,
    val logo: String?,
    val backdrop: List<String>,
    val isMedia: Boolean,
    val rating: Float?,
    val bitrate: Int?,
    val externalUrls: List<ExternalUrl>,
    val year: Int?,
    val providerIds: Map<String, String>,
    val streams: List<StreamInfo>,
    val etag: String?,
)

data class SubtitleTrack(
    val trackId: String,
    val label: String,
    val language: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val externalRef: String?,
)

@Serializable
enum class SortOrder { Ascending, Descending }

@Serializable
enum class SortField {
    Title,
    DatePlayed,
    DateAdded,
    CommunityRating,
    Year,
    IndexNumber,
}

@Serializable
enum class EntryTag {
    Recent,
    Favorite,
    Played,
    Unplayed,
}

@Serializable
data class SearchQuery(
    val term: String = "",
    val years: List<Int>? = null,
    val genres: List<String>? = null,
    val countries: List<String>? = null,
    val studios: List<String>? = null,
    val excludeTypes: Set<EntryType> = emptySet(),
    val startIndex: Int = 0,
    val limit: Int = 100,
    val sortBy: SortField? = null,
    val sortOrder: SortOrder = SortOrder.Ascending,
)

object CatalogRouteTokens {
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"
}
