package com.opentune.provider

// --- Media models ---

enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
    Series,
    Season,
    Episode,
}

sealed class MediaArt {
    data class Http(val url: String) : MediaArt()
    data class DrawableRes(val resId: Int) : MediaArt()
    data class LocalFile(val absolutePath: String) : MediaArt()
    data object None : MediaArt()
}

data class MediaUserData(
    val positionMs: Long,
    val isFavorite: Boolean,
    val played: Boolean,
)

data class MediaListItem(
    val id: String,
    val title: String,
    val kind: MediaEntryKind,
    val cover: MediaArt,
    val userData: MediaUserData? = null,
    val originalTitle: String? = null,
    val genres: List<String>? = null,
    val communityRating: Float? = null,
    val studios: List<String>? = null,
    val etag: String? = null,
    val indexNumber: Int? = null,
)

data class BrowsePageResult(
    val items: List<MediaListItem>,
    val totalCount: Int,
)

data class ExternalUrl(
    val name: String,
    val url: String,
)

data class MediaStreamInfo(
    val index: Int,
    val type: String,
    val codec: String?,
    val displayTitle: String?,
    val language: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
)

data class MediaDetailModel(
    val title: String,
    val overview: String?,
    val logo: MediaArt,
    val backdropImages: List<String>,
    val canPlay: Boolean,
    val communityRating: Float?,
    val bitrate: Int?,
    val externalUrls: List<ExternalUrl>,
    val productionYear: Int?,
    val providerIds: Map<String, String>,
    val mediaStreams: List<MediaStreamInfo>,
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
     * non-null = external ref: Emby = HTTP URL; SMB = file path fed to
     * [com.opentune.provider.PlaybackSpec.resolveExternalSubtitle].
     */
    val externalRef: String?,
)

// --- Route tokens ---

/** Opaque browse/search location tokens shared with catalog routes. */
object CatalogRouteTokens {
    /** Encoded location meaning "show top-level library views" for HTTP-library providers. */
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"
}
