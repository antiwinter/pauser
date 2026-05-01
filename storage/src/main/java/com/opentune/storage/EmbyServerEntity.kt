package com.opentune.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emby_servers")
data class EmbyServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val serverId: String? = null,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val itemId: String,
    val title: String,
    val type: String?,
)

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val key: String,
    val serverId: Long,
    val itemId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "smb_sources")
data class SmbSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val host: String,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String?,
)
