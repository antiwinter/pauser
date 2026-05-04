package com.opentune.provider

// --- Media models -----------------------------------------------------------------------------

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

// --- Catalog source ---------------------------------------------------------------------------

/**
 * Catalog port implemented by provider modules.
 * Keep this surface small; add new ports only when a new screen truly needs them.
 */
interface MediaCatalogSource {
    suspend fun loadBrowsePage(startIndex: Int, limit: Int): BrowsePageResult

    suspend fun searchItems(query: String): List<MediaListItem>

    suspend fun loadDetail(itemKey: String): MediaDetailModel

    fun detailSupportsFavorite(): Boolean

    suspend fun setFavorite(itemKey: String, favorite: Boolean)
}

// --- Binding resolution -----------------------------------------------------------------------

/**
 * Active [MediaCatalogSource] plus per-screen metadata and cleanup (e.g. file-share session).
 */
data class MediaCatalogScreenBinding(
    val catalog: MediaCatalogSource,
    val logTag: String,
    val onDispose: () -> Unit,
    /** Used by browse; empty for search/detail. */
    val subtitle: String = "",
)

sealed interface CatalogResolveResult {
    data class Ok(val binding: MediaCatalogScreenBinding) : CatalogResolveResult
    data class Err(val message: String) : CatalogResolveResult
}

/** UI state after resolving browse/search/detail bindings. */
sealed interface CatalogScreenBindingState {
    data object Loading : CatalogScreenBindingState
    data class Error(val message: String) : CatalogScreenBindingState
    data class Ready(val binding: MediaCatalogScreenBinding) : CatalogScreenBindingState
}

fun CatalogResolveResult.toScreenState(): CatalogScreenBindingState = when (this) {
    is CatalogResolveResult.Ok -> CatalogScreenBindingState.Ready(binding)
    is CatalogResolveResult.Err -> CatalogScreenBindingState.Error(message)
}

data class CatalogBindingDeps(
    val serverStore: ServerStore,
    val favoritesStore: FavoritesStore,
    val progressStore: ProgressStore,
)

interface CatalogBindingPlugin {
    suspend fun browseBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        locationDecoded: String,
    ): CatalogResolveResult

    suspend fun searchBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult

    suspend fun detailBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        itemRefDecoded: String,
    ): CatalogResolveResult
}

// --- Route tokens -------------------------------------------------------------------------------

/** Opaque browse/search location tokens shared with catalog routes. */
object CatalogRouteTokens {
    /** Encoded location meaning “show top-level library views” for HTTP-library providers. */
    const val LIBRARIES_ROOT_SEGMENT: String = "__opentune_library_root__"
}
