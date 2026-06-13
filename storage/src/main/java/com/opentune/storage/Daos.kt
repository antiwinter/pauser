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

    @Query("SELECT * FROM endpoints WHERE proxyId = :proxyId")
    suspend fun getByProxyId(proxyId: String): List<EndpointEntity>

    @Query("DELETE FROM endpoints WHERE endpointId = :endpointId")
    suspend fun deleteByEndpointId(endpointId: String)
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxies ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<ProxyEntity>>

    @Query("SELECT * FROM proxies ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<ProxyEntity>

    @Query("SELECT * FROM proxies WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProxyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProxyEntity)

    @Update
    suspend fun update(entity: ProxyEntity)

    @Query("DELETE FROM proxies WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EntryStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntryStateEntity)

    @Query("SELECT * FROM media_state WHERE endpointId = :endpointId AND itemRef = :itemRef LIMIT 1")
    suspend fun get(endpointId: String, itemRef: String): EntryStateEntity?

    @Query("SELECT * FROM media_state WHERE endpointId = :endpointId ORDER BY updatedAtEpochMs DESC")
    fun observeForEndpoint(endpointId: String): Flow<List<EntryStateEntity>>

    @Query("SELECT * FROM media_state WHERE isFavorite = 1 ORDER BY updatedAtEpochMs DESC")
    fun observeAllFavorites(): Flow<List<EntryStateEntity>>

    @Query("UPDATE media_state SET positionMs = :positionMs, updatedAtEpochMs = :now WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun updatePosition(endpointId: String, itemRef: String, positionMs: Long, now: Long)

    @Query("UPDATE media_state SET playbackSpeed = :speed, updatedAtEpochMs = :now WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun updateSpeed(endpointId: String, itemRef: String, speed: Float, now: Long)

    @Query("UPDATE media_state SET isFavorite = :isFavorite, title = :title, type = :type, updatedAtEpochMs = :now WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun updateFavorite(endpointId: String, itemRef: String, isFavorite: Boolean, title: String?, type: String?, now: Long)

    @Query("UPDATE media_state SET selectedSubtitleTrackId = :id, updatedAtEpochMs = :now WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun updateSubtitleTrack(endpointId: String, itemRef: String, id: String?, now: Long)

    @Query("UPDATE media_state SET selectedAudioTrackId = :id, updatedAtEpochMs = :now WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun updateAudioTrack(endpointId: String, itemRef: String, id: String?, now: Long)

    @Query("DELETE FROM media_state WHERE endpointId = :endpointId")
    suspend fun deleteByEndpoint(endpointId: String)

    @Query("DELETE FROM media_state WHERE endpointId = :endpointId AND itemRef = :itemRef")
    suspend fun delete(endpointId: String, itemRef: String)
}
