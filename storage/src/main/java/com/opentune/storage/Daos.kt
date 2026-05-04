package com.opentune.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers WHERE providerId = :providerId ORDER BY id ASC")
    fun observeByProvider(providerId: String): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY id ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FavoriteDao {
    @Query(
        "SELECT * FROM favorites WHERE providerId = :providerId AND sourceId = :sourceId ORDER BY itemId ASC",
    )
    fun observeForSource(providerId: String, sourceId: Long): Flow<List<FavoriteEntity>>

    @Query(
        "SELECT * FROM favorites WHERE providerId = :providerId AND sourceId = :sourceId AND itemId = :itemId LIMIT 1",
    )
    suspend fun find(providerId: String, sourceId: Long, itemId: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query(
        "DELETE FROM favorites WHERE providerId = :providerId AND sourceId = :sourceId AND itemId = :itemId",
    )
    suspend fun delete(providerId: String, sourceId: Long, itemId: String)
}

@Dao
interface PlaybackProgressDao {
    @Query(
        "SELECT * FROM playback_progress WHERE providerId = :providerId AND sourceId = :sourceId " +
            "ORDER BY updatedAtEpochMs DESC LIMIT :limit",
    )
    fun observeRecentForSource(providerId: String, sourceId: Long, limit: Int = 20): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE `key` = :key")
    suspend fun delete(key: String)
}
