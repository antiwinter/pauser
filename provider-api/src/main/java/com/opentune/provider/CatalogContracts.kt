package com.opentune.provider

// --- Media models ---

enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
}

sealed class MediaCover {
    data class Http(val url: String) : MediaCover()
    data class DrawableRes(val resId: Int) : MediaCover()
    data class LocalFile(val absolutePath: String) : MediaCover()
    data object None : MediaCover()
}

data class MediaListItem(
    val id: String,
    val title: String,
    val kind: MediaEntryKind,
    val cover: MediaCover,
)

data class BrowsePageResult(
    val items: List<MediaListItem>,
    val totalCount: Int,
)

data class MediaDetailModel(
    val itemKey: String,
    val title: String,
    val synopsis: String?,
    val cover: MediaCover,
    val canPlay: Boolean,
    /** Resume position from remote source; 0 if none. App layer overwrites with local value. */
    val resumePositionMs: Long,
    val favoriteSupported: Boolean,
    /** Favorite state from remote source. App layer may override with local state. */
    val isFavorite: Boolean,
)

// --- Route tokens ---

/** Opaque browse/search location tokens shared with catalog routes. */
object CatalogRouteTokens {
    /** Encoded location meaning "show top-level library views" for HTTP-library providers. */
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"
}
