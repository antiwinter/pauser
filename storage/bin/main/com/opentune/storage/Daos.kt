package com.opentune.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers WHERE providerType = :providerType ORDER BY createdAtEpochMs ASC")
    fun observeByProvider(providerType: String): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getBySourceId(sourceId: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(server: ServerEntity)

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)
}

@Dao
interface MediaStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaStateEntity)

    @Query("SELECT * FROM media_state WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(providerType: String, sourceId: String, itemId: String): MediaStateEntity?

    @Query("SELECT * FROM media_state WHERE providerType = :providerType AND sourceId = :sourceId ORDER BY updatedAtEpochMs DESC")
    fun observeForSource(providerType: String, sourceId: String): Flow<List<MediaStateEntity>>

    @Query("SELECT * FROM media_state WHERE isFavorite = 1 ORDER BY updatedAtEpochMs DESC")
    fun observeAllFavorites(): Flow<List<MediaStateEntity>>

    @Query("UPDATE media_state SET positionMs = :positionMs, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updatePosition(providerType: String, sourceId: String, itemId: String, positionMs: Long, now: Long)

    @Query("UPDATE media_state SET playbackSpeed = :speed, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateSpeed(providerType: String, sourceId: String, itemId: String, speed: Float, now: Long)

    @Query("UPDATE media_state SET isFavorite = :isFavorite, title = :title, type = :type, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateFavorite(providerType: String, sourceId: String, itemId: String, isFavorite: Boolean, title: String?, type: String?, now: Long)

    @Query("UPDATE media_state SET coverCachePath = :path, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateCoverCache(providerType: String, sourceId: String, itemId: String, path: String?, now: Long)

    @Query("UPDATE media_state SET selectedSubtitleTrackId = :id, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateSubtitleTrack(providerType: String, sourceId: String, itemId: String, id: String?, now: Long)

    @Query("UPDATE media_state SET selectedAudioTrackId = :id, updatedAtEpochMs = :now WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun updateAudioTrack(providerType: String, sourceId: String, itemId: String, id: String?, now: Long)

    @Query("DELETE FROM media_state WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("DELETE FROM media_state WHERE providerType = :providerType AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(providerType: String, sourceId: String, itemId: String)
}
