package com.opentune.provider

import kotlinx.coroutines.flow.Flow

data class FavoriteRow(
    val itemId: String,
    val title: String,
    val type: String?,
)

interface FavoritesStore {
    fun observeFavorites(providerId: String, sourceId: Long): Flow<List<FavoriteRow>>

    suspend fun isFavorite(providerId: String, sourceId: Long, itemId: String): Boolean

    suspend fun setFavorite(
        providerId: String,
        sourceId: Long,
        itemId: String,
        title: String,
        type: String?,
        favorite: Boolean,
    )
}
