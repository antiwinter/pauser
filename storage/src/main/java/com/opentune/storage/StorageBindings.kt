package com.opentune.storage

import com.opentune.provider.FavoriteRow
import com.opentune.provider.FavoritesStore
import com.opentune.provider.ProgressStore
import com.opentune.provider.ServerRecord
import com.opentune.provider.ServerStore
import com.opentune.provider.progressPersistenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class OpenTuneStorageBindings(
    val serverStore: ServerStore,
    val favoritesStore: FavoritesStore,
    val progressStore: ProgressStore,
) {
    companion object {
        fun create(database: OpenTuneDatabase): OpenTuneStorageBindings =
            OpenTuneStorageBindings(
                serverStore = RoomServerStore(database),
                favoritesStore = RoomFavoritesStore(database),
                progressStore = RoomProgressStore(database),
            )
    }
}

private class RoomServerStore(
    private val db: OpenTuneDatabase,
) : ServerStore {
    private val dao: ServerDao get() = db.serverDao()

    override suspend fun insert(providerId: String, displayName: String, fieldsJson: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            ServerEntity(
                id = 0,
                providerId = providerId,
                displayName = displayName,
                fieldsJson = fieldsJson,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    override suspend fun update(sourceId: Long, displayName: String, fieldsJson: String) {
        val existing = dao.getById(sourceId) ?: return
        val now = System.currentTimeMillis()
        dao.update(
            existing.copy(
                displayName = displayName,
                fieldsJson = fieldsJson,
                updatedAtEpochMs = now,
            ),
        )
    }

    override suspend fun delete(sourceId: Long) {
        dao.deleteById(sourceId)
    }

    override fun observeByProvider(providerId: String): Flow<List<ServerRecord>> =
        dao.observeByProvider(providerId).map { list -> list.map { it.toRecord() } }

    override suspend fun get(sourceId: Long): ServerRecord? =
        dao.getById(sourceId)?.toRecord()
}

private fun ServerEntity.toRecord(): ServerRecord =
    ServerRecord(
        id = id,
        providerId = providerId,
        displayName = displayName,
        fieldsJson = fieldsJson,
    )

private class RoomFavoritesStore(
    private val db: OpenTuneDatabase,
) : FavoritesStore {
    private val dao: FavoriteDao get() = db.favoriteDao()

    override fun observeFavorites(providerId: String, sourceId: Long): Flow<List<FavoriteRow>> =
        dao.observeForSource(providerId, sourceId).map { rows ->
            rows.map { FavoriteRow(itemId = it.itemId, title = it.title, type = it.type) }
        }

    override suspend fun isFavorite(providerId: String, sourceId: Long, itemId: String): Boolean =
        dao.find(providerId, sourceId, itemId) != null

    override suspend fun setFavorite(
        providerId: String,
        sourceId: Long,
        itemId: String,
        title: String,
        type: String?,
        favorite: Boolean,
    ) {
        if (favorite) {
            dao.insert(
                FavoriteEntity(
                    providerId = providerId,
                    sourceId = sourceId,
                    itemId = itemId,
                    title = title,
                    type = type,
                ),
            )
        } else {
            dao.delete(providerId, sourceId, itemId)
        }
    }
}

private class RoomProgressStore(
    private val db: OpenTuneDatabase,
) : ProgressStore {
    private val dao: PlaybackProgressDao get() = db.playbackProgressDao()

    override suspend fun getPositionMs(providerId: String, sourceId: Long, itemId: String): Long? =
        dao.get(progressPersistenceKey(providerId, sourceId, itemId))?.positionMs

    override suspend fun upsert(providerId: String, sourceId: Long, itemId: String, positionMs: Long) {
        dao.upsert(
            PlaybackProgressEntity(
                key = progressPersistenceKey(providerId, sourceId, itemId),
                providerId = providerId,
                sourceId = sourceId,
                itemId = itemId,
                positionMs = positionMs,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }
}
