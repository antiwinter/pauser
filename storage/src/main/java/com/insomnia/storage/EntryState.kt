package com.insomnia.storage

import kotlinx.coroutines.flow.Flow

fun decodeSeriesProgress(packed: Long): Pair<Int, Int> =
    (packed / 10000).toInt() to (packed % 10000).toInt()

fun encodeSeriesProgress(seasonIndex: Int, episodeIndex: Int): Long =
    seasonIndex.toLong() * 10000 + episodeIndex

data class EntryStateKey(
    val endpointId: String,
    val itemRef: String,
)

class EntryStateStore(private val db: InsomniaDatabase) {

    private val dao: EntryStateDao get() = db.entryStateDao()

    private suspend fun ensureRow(key: EntryStateKey) {
        val exists = dao.get(key.endpointId, key.itemRef)
        if (exists == null) {
            dao.upsert(
                EntryStateEntity(
                    endpointId = key.endpointId,
                    itemRef = key.itemRef,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun get(key: EntryStateKey): EntryStateEntity? =
        dao.get(key.endpointId, key.itemRef)

    suspend fun get(endpointId: String, itemRef: String): EntryStateEntity? =
        get(EntryStateKey(endpointId, itemRef))

    suspend fun upsertPosition(key: EntryStateKey, positionMs: Long) {
        ensureRow(key)
        dao.updatePosition(key.endpointId, key.itemRef, positionMs, System.currentTimeMillis())
    }

    suspend fun upsertSpeed(key: EntryStateKey, speed: Float) {
        ensureRow(key)
        dao.updateSpeed(key.endpointId, key.itemRef, speed, System.currentTimeMillis())
    }

    suspend fun upsertFavorite(key: EntryStateKey, isFavorite: Boolean, title: String? = null, type: String? = null) {
        ensureRow(key)
        dao.updateFavorite(key.endpointId, key.itemRef, isFavorite, title, type, System.currentTimeMillis())
    }

    suspend fun upsertSubtitleTrack(key: EntryStateKey, trackId: String?) {
        ensureRow(key)
        dao.updateSubtitleTrack(key.endpointId, key.itemRef, trackId, System.currentTimeMillis())
    }

    suspend fun upsertAudioTrack(key: EntryStateKey, trackId: String?) {
        ensureRow(key)
        dao.updateAudioTrack(key.endpointId, key.itemRef, trackId, System.currentTimeMillis())
    }

    suspend fun upsertRecentMeta(
        key: EntryStateKey,
        title: String?,
        type: String?,
        cover: String?,
        etag: String?,
    ) {
        ensureRow(key)
        dao.updateRecentMeta(
            key.endpointId,
            key.itemRef,
            title,
            type,
            cover,
            etag,
            System.currentTimeMillis(),
        )
    }

    fun observeForEndpoint(endpointId: String): Flow<List<EntryStateEntity>> =
        dao.observeForEndpoint(endpointId)

    suspend fun queryRecentForEndpoint(endpointId: String, limit: Int): List<EntryStateEntity> =
        dao.queryRecentForEndpoint(endpointId, limit)

    fun observeAllFavorites(): Flow<List<EntryStateEntity>> =
        dao.observeAllFavorites()

    suspend fun deleteByEndpoint(endpointId: String) {
        dao.deleteByEndpoint(endpointId)
    }
}
