package com.opentune.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EndpointDao {
    @Query("SELECT * FROM endpoints WHERE protocol = :protocol ORDER BY createdAtEpochMs ASC")
    fun observeByProvider(protocol: String): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints WHERE endpointId = :endpointId LIMIT 1")
    suspend fun getByEndpointId(endpointId: String): EndpointEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(endpoint: EndpointEntity)

    @Update
    suspend fun update(endpoint: EndpointEntity)

    @Query("DELETE FROM endpoints WHERE endpointId = :endpointId")
    suspend fun deleteByEndpointId(endpointId: String)
}

@Dao
interface EntryStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntryStateEntity)

    @Query("SELECT * FROM media_state WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId LIMIT 1")
    suspend fun get(protocol: String, endpointId: String, itemId: String): EntryStateEntity?

    @Query("SELECT * FROM media_state WHERE protocol = :protocol AND endpointId = :endpointId ORDER BY updatedAtEpochMs DESC")
    fun observeForEndpoint(protocol: String, endpointId: String): Flow<List<EntryStateEntity>>

    @Query("SELECT * FROM media_state WHERE isFavorite = 1 ORDER BY updatedAtEpochMs DESC")
    fun observeAllFavorites(): Flow<List<EntryStateEntity>>

    @Query("UPDATE media_state SET positionMs = :positionMs, updatedAtEpochMs = :now WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun updatePosition(protocol: String, endpointId: String, itemId: String, positionMs: Long, now: Long)

    @Query("UPDATE media_state SET playbackSpeed = :speed, updatedAtEpochMs = :now WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun updateSpeed(protocol: String, endpointId: String, itemId: String, speed: Float, now: Long)

    @Query("UPDATE media_state SET isFavorite = :isFavorite, title = :title, type = :type, updatedAtEpochMs = :now WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun updateFavorite(protocol: String, endpointId: String, itemId: String, isFavorite: Boolean, title: String?, type: String?, now: Long)

    @Query("UPDATE media_state SET selectedSubtitleTrackId = :id, updatedAtEpochMs = :now WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun updateSubtitleTrack(protocol: String, endpointId: String, itemId: String, id: String?, now: Long)

    @Query("UPDATE media_state SET selectedAudioTrackId = :id, updatedAtEpochMs = :now WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun updateAudioTrack(protocol: String, endpointId: String, itemId: String, id: String?, now: Long)

    @Query("DELETE FROM media_state WHERE endpointId = :endpointId")
    suspend fun deleteByEndpoint(endpointId: String)

    @Query("DELETE FROM media_state WHERE protocol = :protocol AND endpointId = :endpointId AND itemId = :itemId")
    suspend fun delete(protocol: String, endpointId: String, itemId: String)
}
