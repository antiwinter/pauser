package com.opentune.storage

import kotlinx.coroutines.flow.Flow

data class MediaStateKey(
    val protocol: String,
    val sourceId: String,
    val itemRef: String,
)

data class MediaStateSnapshot(
    val protocol: String,
    val sourceId: String,
    val itemId: String,
    val positionMs: Long,
    val playbackSpeed: Float,
    val isFavorite: Boolean,
    val title: String?,
    val type: String?,
    val coverCachePath: String?,
    val selectedSubtitleTrackId: String?,
    val selectedAudioTrackId: String?,
)

interface UserMediaStateStore {
    suspend fun get(protocol: String, sourceId: String, itemId: String): MediaStateSnapshot?
    suspend fun upsertPosition(protocol: String, sourceId: String, itemId: String, positionMs: Long)
    suspend fun upsertSpeed(protocol: String, sourceId: String, itemId: String, speed: Float)
    suspend fun upsertFavorite(
        protocol: String,
        sourceId: String,
        itemId: String,
        isFavorite: Boolean,
        title: String? = null,
        type: String? = null,
    )
    suspend fun upsertCoverCache(protocol: String, sourceId: String, itemId: String, path: String?)
    suspend fun upsertSubtitleTrack(protocol: String, sourceId: String, itemId: String, trackId: String?)
    suspend fun upsertAudioTrack(protocol: String, sourceId: String, itemId: String, trackId: String?)
    fun observeForSource(protocol: String, sourceId: String): Flow<List<MediaStateSnapshot>>
    fun observeAllFavorites(): Flow<List<MediaStateSnapshot>>
    suspend fun deleteBySource(sourceId: String)
}

suspend fun UserMediaStateStore.get(key: MediaStateKey): MediaStateSnapshot? =
    get(key.protocol, key.sourceId, key.itemRef)

suspend fun UserMediaStateStore.upsertPosition(key: MediaStateKey, positionMs: Long) =
    upsertPosition(key.protocol, key.sourceId, key.itemRef, positionMs)

suspend fun UserMediaStateStore.upsertSpeed(key: MediaStateKey, speed: Float) =
    upsertSpeed(key.protocol, key.sourceId, key.itemRef, speed)

suspend fun UserMediaStateStore.upsertFavorite(key: MediaStateKey, isFavorite: Boolean) =
    upsertFavorite(key.protocol, key.sourceId, key.itemRef, isFavorite)

suspend fun UserMediaStateStore.upsertSubtitleTrack(key: MediaStateKey, trackId: String?) =
    upsertSubtitleTrack(key.protocol, key.sourceId, key.itemRef, trackId)

suspend fun UserMediaStateStore.upsertAudioTrack(key: MediaStateKey, trackId: String?) =
    upsertAudioTrack(key.protocol, key.sourceId, key.itemRef, trackId)
