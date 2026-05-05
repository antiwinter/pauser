package com.opentune.storage

import kotlinx.coroutines.flow.Flow

data class MediaStateKey(
    val providerType: String,
    val sourceId: String,
    val itemRef: String,
)

data class MediaStateSnapshot(
    val providerType: String,
    val sourceId: String,
    val itemId: String,
    val positionMs: Long,
    val playbackSpeed: Float,
    val isFavorite: Boolean,
    val title: String?,
    val type: String?,
    val coverThumbPath: String?,
)

interface UserMediaStateStore {
    suspend fun get(providerType: String, sourceId: String, itemId: String): MediaStateSnapshot?
    suspend fun upsertPosition(providerType: String, sourceId: String, itemId: String, positionMs: Long)
    suspend fun upsertSpeed(providerType: String, sourceId: String, itemId: String, speed: Float)
    suspend fun upsertFavorite(
        providerType: String,
        sourceId: String,
        itemId: String,
        isFavorite: Boolean,
        title: String? = null,
        type: String? = null,
    )
    suspend fun upsertCoverThumb(providerType: String, sourceId: String, itemId: String, path: String?)
    fun observeForSource(providerType: String, sourceId: String): Flow<List<MediaStateSnapshot>>
    fun observeAllFavorites(): Flow<List<MediaStateSnapshot>>
    suspend fun deleteBySource(sourceId: String)
}

suspend fun UserMediaStateStore.get(key: MediaStateKey): MediaStateSnapshot? =
    get(key.providerType, key.sourceId, key.itemRef)

suspend fun UserMediaStateStore.upsertPosition(key: MediaStateKey, positionMs: Long) =
    upsertPosition(key.providerType, key.sourceId, key.itemRef, positionMs)

suspend fun UserMediaStateStore.upsertSpeed(key: MediaStateKey, speed: Float) =
    upsertSpeed(key.providerType, key.sourceId, key.itemRef, speed)

suspend fun UserMediaStateStore.upsertFavorite(key: MediaStateKey, isFavorite: Boolean) =
    upsertFavorite(key.providerType, key.sourceId, key.itemRef, isFavorite)
