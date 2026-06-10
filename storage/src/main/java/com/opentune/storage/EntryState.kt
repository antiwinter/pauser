package com.opentune.storage

import kotlinx.coroutines.flow.Flow

fun decodeSeriesProgress(packed: Long): Pair<Int, Int> =
    (packed ushr 32).toInt() to (packed and 0xFFFF_FFFFL).toInt()

data class EntryStateKey(
    val endpointId: String,
    val itemRef: String,
)

class EntryStateStore(private val db: OpenTuneDatabase) {

    private val dao: EntryStateDao get() = db.entryStateDao()

    private suspend fun ensureRow(key: EntryStateKey, protocol: String = "") {
        val exists = dao.get(key.endpointId, key.itemRef)
        if (exists == null) {
            dao.upsert(
                EntryStateEntity(
                    protocol = protocol,
                    endpointId = key.endpointId,
                    itemId = key.itemRef,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun get(key: EntryStateKey): EntryStateEntity? =
        dao.get(key.endpointId, key.itemRef)

    suspend fun get(endpointId: String, itemId: String): EntryStateEntity? =
        get(EntryStateKey(endpointId, itemId))

    suspend fun upsertPosition(key: EntryStateKey, positionMs: Long, protocol: String = "") {
        ensureRow(key, protocol)
        dao.updatePosition(key.endpointId, key.itemRef, positionMs, System.currentTimeMillis())
    }

    suspend fun upsertSpeed(key: EntryStateKey, speed: Float, protocol: String = "") {
        ensureRow(key, protocol)
        dao.updateSpeed(key.endpointId, key.itemRef, speed, System.currentTimeMillis())
    }

    suspend fun upsertFavorite(key: EntryStateKey, isFavorite: Boolean, title: String? = null, type: String? = null, protocol: String = "") {
        ensureRow(key, protocol)
        dao.updateFavorite(key.endpointId, key.itemRef, isFavorite, title, type, System.currentTimeMillis())
    }

    suspend fun upsertSubtitleTrack(key: EntryStateKey, trackId: String?, protocol: String = "") {
        ensureRow(key, protocol)
        dao.updateSubtitleTrack(key.endpointId, key.itemRef, trackId, System.currentTimeMillis())
    }

    suspend fun upsertAudioTrack(key: EntryStateKey, trackId: String?, protocol: String = "") {
        ensureRow(key, protocol)
        dao.updateAudioTrack(key.endpointId, key.itemRef, trackId, System.currentTimeMillis())
    }

    suspend fun upsertSeriesProgress(key: EntryStateKey, seasonNumber: Int, episodeNumber: Int, protocol: String = "") {
        ensureRow(key, protocol)
        val packed = (seasonNumber.toLong() shl 32) or episodeNumber.toLong()
        dao.updatePosition(key.endpointId, key.itemRef, packed, System.currentTimeMillis())
    }

    fun observeForEndpoint(endpointId: String): Flow<List<EntryStateEntity>> =
        dao.observeForEndpoint(endpointId)

    fun observeAllFavorites(): Flow<List<EntryStateEntity>> =
        dao.observeAllFavorites()

    suspend fun deleteByEndpoint(endpointId: String) {
        dao.deleteByEndpoint(endpointId)
    }
}
