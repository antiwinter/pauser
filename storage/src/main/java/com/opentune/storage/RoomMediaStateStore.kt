package com.opentune.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMediaStateStore(private val db: OpenTuneDatabase) : UserMediaStateStore {

    private val dao: MediaStateDao get() = db.mediaStateDao()

    private fun MediaStateEntity.toSnapshot(): MediaStateSnapshot =
        MediaStateSnapshot(
            protocol = protocol,
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

    private suspend fun ensureRow(protocol: String, sourceId: String, itemId: String) {
        val exists = dao.get(protocol, sourceId, itemId)
        if (exists == null) {
            dao.upsert(
                MediaStateEntity(
                    protocol = protocol,
                    sourceId = sourceId,
                    itemId = itemId,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun get(protocol: String, sourceId: String, itemId: String): MediaStateSnapshot? =
        dao.get(protocol, sourceId, itemId)?.toSnapshot()

    override suspend fun upsertPosition(
        protocol: String,
        sourceId: String,
        itemId: String,
        positionMs: Long,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updatePosition(protocol, sourceId, itemId, positionMs, System.currentTimeMillis())
    }

    override suspend fun upsertSpeed(
        protocol: String,
        sourceId: String,
        itemId: String,
        speed: Float,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updateSpeed(protocol, sourceId, itemId, speed, System.currentTimeMillis())
    }

    override suspend fun upsertFavorite(
        protocol: String,
        sourceId: String,
        itemId: String,
        isFavorite: Boolean,
        title: String?,
        type: String?,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updateFavorite(protocol, sourceId, itemId, isFavorite, title, type, System.currentTimeMillis())
    }

    override suspend fun upsertCoverCache(
        protocol: String,
        sourceId: String,
        itemId: String,
        path: String?,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updateCoverCache(protocol, sourceId, itemId, path, System.currentTimeMillis())
    }

    override suspend fun upsertSubtitleTrack(
        protocol: String,
        sourceId: String,
        itemId: String,
        trackId: String?,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updateSubtitleTrack(protocol, sourceId, itemId, trackId, System.currentTimeMillis())
    }

    override suspend fun upsertAudioTrack(
        protocol: String,
        sourceId: String,
        itemId: String,
        trackId: String?,
    ) {
        ensureRow(protocol, sourceId, itemId)
        dao.updateAudioTrack(protocol, sourceId, itemId, trackId, System.currentTimeMillis())
    }

    override fun observeForSource(protocol: String, sourceId: String): Flow<List<MediaStateSnapshot>> =
        dao.observeForSource(protocol, sourceId).map { list -> list.map { it.toSnapshot() } }

    override fun observeAllFavorites(): Flow<List<MediaStateSnapshot>> =
        dao.observeAllFavorites().map { list -> list.map { it.toSnapshot() } }

    override suspend fun deleteBySource(sourceId: String) {
        dao.deleteBySource(sourceId)
    }
}
