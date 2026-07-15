package com.insomnia.content.contract

import com.insomnia.player.PlaybackSource
import kotlinx.serialization.Serializable

/** Entry type is a plain string. Common values: "Folder", "Movie", "Series", "Episode", "Image", "Digipak", "Season", "Video", "Audio", "Unknown". */
typealias EntryType = String

@Serializable
data class EntryUserData(
    /** Playback resume position; null when the provider does not report it (e.g. series sXeY). */
    val positionMs: Long? = null,
    val isFavorite: Boolean,
    val played: Boolean,
)

@Serializable
data class EntryInfo(
    val ref: String,
    val title: String,
    val type: EntryType,
    val cover: String? = null,
    val userData: EntryUserData? = null,
    val originalTitle: String? = null,
    val genres: List<String>? = null,
    val communityRating: Float? = null,
    val studios: List<String>? = null,
    val actors: List<String>? = null,
    val directors: List<String>? = null,
    val areas: List<String>? = null,
    val languages: List<String>? = null,
    val etag: String? = null,
    val indexNumber: Int? = null,
    val overview: String? = null,
    val childCount: Int? = null,
    val parentRef: String? = null,
    val seriesRef: String? = null,
    val seasonNumber: Int? = null,
    // Detail fields (previously in EntryDetail)
    val logo: String? = null,
    val backdrop: List<String> = emptyList(),
    val bitrate: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val officialRating: String? = null,
    val quality: String? = null,
    val filename: String? = null,
    val sources: List<PlaybackSource>? = null,
)

data class EntryList(
    val items: List<EntryInfo>,
    val totalCount: Int,
)

data class QueryOptions(
    val sortBy: SortField? = null,
    val sortOrder: SortOrder = SortOrder.Ascending,
    val recursive: Boolean = false,
    val filterByType: String? = null,
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
    val excludeTypes: Set<String> = emptySet(),
    val startIndex: Int = 0,
    val limit: Int = 100,
    val sortBy: SortField? = null,
    val sortOrder: SortOrder = SortOrder.Ascending,
)

object CatalogRouteTokens {
    const val LIBRARIES_ROOT_SEGMENT: String = "__insomnia_library_root__"
}
