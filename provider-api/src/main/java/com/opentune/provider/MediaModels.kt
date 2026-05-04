package com.opentune.provider

enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
}

sealed class MediaCover {
    data class Http(val url: String) : MediaCover()
    data class DrawableRes(val resId: Int) : MediaCover()
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
    /** Resume position when [canPlay]; 0 if none. */
    val resumePositionMs: Long,
    val favoriteSupported: Boolean,
    val isFavorite: Boolean,
)
