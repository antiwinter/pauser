package com.opentune.provider

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
