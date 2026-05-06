package com.opentune.provider

// --- Media models ---

enum class MediaEntryKind {
    Folder,
    Playable,
    Other,
}

sealed class MediaArt {
    data class Http(val url: String) : MediaArt()
    data class DrawableRes(val resId: Int) : MediaArt()
    data class LocalFile(val absolutePath: String) : MediaArt()
    data object None : MediaArt()
}

data class MediaListItem(
    val id: String,
    val title: String,
    val kind: MediaEntryKind,
    val cover: MediaArt,
)

data class BrowsePageResult(
    val items: List<MediaListItem>,
    val totalCount: Int,
)

data class MediaDetailModel(
    val itemKey: String,
    val title: String,
    val synopsis: String?,
    val cover: MediaArt,
    val poster: MediaArt,
    val canPlay: Boolean,
    /** Resume position from remote source; 0 if none. App layer overwrites with local value. */
    val resumePositionMs: Long,
    val favoriteSupported: Boolean,
    /** Favorite state from remote source. App layer may override with local state. */
    val isFavorite: Boolean,
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
