package com.opentune.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMediaStateStore(private val db: OpenTuneDatabase) : UserMediaStateStore {

    private val dao: MediaStateDao get() = db.mediaStateDao()

    private fun MediaStateEntity.toSnapshot(): MediaStateSnapshot =
        MediaStateSnapshot(
            providerType = providerType,
            sourceId = sourceId,
            itemId = itemId,
            positionMs = positionMs,
            playbackSpeed = playbackSpeed,
            isFavorite = isFavorite,
            title = title,
            type = type,
            coverCachePath = coverCachePath,
            selectedSubtitleTrackId = selectedSubtitleTrackId,
            selectedAudioTrackId = selectedAudioTrackId,
        )

    private suspend fun ensureRow(providerType: String, sourceId: String, itemId: String) {
        val exists = dao.get(providerType, sourceId, itemId)
        if (exists == null) {
            dao.upsert(
                MediaStateEntity(
                    providerType = providerType,
                    sourceId = sourceId,
                    itemId = itemId,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun get(providerType: String, sourceId: String, itemId: String): MediaStateSnapshot? =
        dao.get(providerType, sourceId, itemId)?.toSnapshot()

    override suspend fun upsertPosition(
        providerType: String,
        sourceId: String,
        itemId: String,
        positionMs: Long,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updatePosition(providerType, sourceId, itemId, positionMs, System.currentTimeMillis())
    }

    override suspend fun upsertSpeed(
        providerType: String,
        sourceId: String,
        itemId: String,
        speed: Float,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updateSpeed(providerType, sourceId, itemId, speed, System.currentTimeMillis())
    }

    override suspend fun upsertFavorite(
        providerType: String,
        sourceId: String,
        itemId: String,
        isFavorite: Boolean,
        title: String?,
        type: String?,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updateFavorite(providerType, sourceId, itemId, isFavorite, title, type, System.currentTimeMillis())
    }

    override suspend fun upsertCoverCache(
        providerType: String,
        sourceId: String,
        itemId: String,
        path: String?,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updateCoverCache(providerType, sourceId, itemId, path, System.currentTimeMillis())
    }

    override suspend fun upsertSubtitleTrack(
        providerType: String,
        sourceId: String,
        itemId: String,
        trackId: String?,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updateSubtitleTrack(providerType, sourceId, itemId, trackId, System.currentTimeMillis())
    }

    override suspend fun upsertAudioTrack(
        providerType: String,
        sourceId: String,
        itemId: String,
        trackId: String?,
    ) {
        ensureRow(providerType, sourceId, itemId)
        dao.updateAudioTrack(providerType, sourceId, itemId, trackId, System.currentTimeMillis())
    }

    override fun observeForSource(providerType: String, sourceId: String): Flow<List<MediaStateSnapshot>> =
        dao.observeForSource(providerType, sourceId).map { list -> list.map { it.toSnapshot() } }

    override fun observeAllFavorites(): Flow<List<MediaStateSnapshot>> =
        dao.observeAllFavorites().map { list -> list.map { it.toSnapshot() } }

    override suspend fun deleteBySource(sourceId: String) {
        dao.deleteBySource(sourceId)
    }
}
