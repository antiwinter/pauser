package com.opentune.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val displayName: String,
    val fieldsJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["providerId", "sourceId", "itemId"],
)
data class FavoriteEntity(
    val providerId: String,
    val sourceId: Long,
    val itemId: String,
    val title: String,
    val type: String?,
)

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val key: String,
    val providerId: String,
    val sourceId: Long,
    val itemId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
)
