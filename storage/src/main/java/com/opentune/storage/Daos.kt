package com.opentune.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmbyServerDao {
    @Query("SELECT * FROM emby_servers ORDER BY id ASC")
    fun observeAll(): Flow<List<EmbyServerEntity>>

    @Query("SELECT * FROM emby_servers ORDER BY id ASC")
    suspend fun getAll(): List<EmbyServerEntity>

    @Query("SELECT * FROM emby_servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EmbyServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: EmbyServerEntity): Long

    @Update
    suspend fun update(server: EmbyServerEntity)

    @Query("DELETE FROM emby_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE serverId = :serverId ORDER BY id DESC")
    fun observeForServer(serverId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun find(serverId: Long, itemId: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: Long, itemId: String)
}

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE serverId = :serverId ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(serverId: Long, limit: Int = 20): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface SmbSourceDao {
    @Query("SELECT * FROM smb_sources ORDER BY id ASC")
    fun observeAll(): Flow<List<SmbSourceEntity>>

    @Query("SELECT * FROM smb_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmbSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SmbSourceEntity): Long

    @Update
    suspend fun update(source: SmbSourceEntity)

    @Query("DELETE FROM smb_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}
