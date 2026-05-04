package com.opentune.provider

import kotlinx.coroutines.flow.Flow

data class ServerRecord(
    val id: Long,
    val providerId: String,
    val displayName: String,
    val fieldsJson: String,
)

interface ServerStore {
    suspend fun insert(providerId: String, displayName: String, fieldsJson: String): Long

    suspend fun update(sourceId: Long, displayName: String, fieldsJson: String)

    suspend fun delete(sourceId: Long)

    fun observeByProvider(providerId: String): Flow<List<ServerRecord>>

    suspend fun get(sourceId: Long): ServerRecord?
}

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

interface ProgressStore {
    suspend fun getPositionMs(providerId: String, sourceId: Long, itemId: String): Long?

    suspend fun upsert(providerId: String, sourceId: Long, itemId: String, positionMs: Long)
}
