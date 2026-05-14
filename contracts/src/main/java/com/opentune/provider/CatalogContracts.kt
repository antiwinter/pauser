package com.opentune.provider

// --- Media models ---

enum class EntryType {
    Folder,
    Playable,
    Other,
    Series,
    Season,
    Episode,
}

data class EntryUserData(
    val positionMs: Long,
    val isFavorite: Boolean,
    val played: Boolean,
)

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
    /**
     * null = embedded (ExoPlayer selects natively via trackSelectionParameters).
     * non-null = external URI the player loads directly.
     * HTTP(S): use [PlaybackSpec.headers] on the same spec for auth when loading this URI.
     * File-based providers (SMB) supply file:// URIs pointing to local cache.
     */
    val externalRef: String?,
)

// --- Route tokens ---

/** Opaque browse/search location tokens shared with catalog routes. */
object CatalogRouteTokens {
    /** Encoded location meaning "show top-level library views" for HTTP-library providers. */
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"
}
