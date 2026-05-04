package com.opentune.app.ui.catalog

import androidx.compose.runtime.Immutable

@Immutable
enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
}

@Immutable
sealed class MediaCover {
    data class Http(val url: String) : MediaCover()
    data class DrawableRes(val resId: Int) : MediaCover()
    data object None : MediaCover()
}

@Immutable
data class MediaListItem(
    val id: String,
    val title: String,
    val kind: MediaEntryKind,
    val cover: MediaCover,
)

@Immutable
data class BrowsePageResult(
    val items: List<MediaListItem>,
    val totalCount: Int,
)

@Immutable
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
