package com.opentune.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val sourceId: String,
    val providerType: String,
    val displayName: String,
    val fieldsJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "media_state",
    primaryKeys = ["providerType", "sourceId", "itemId"],
)
data class MediaStateEntity(
    val providerType: String,
    val sourceId: String,
    val itemId: String,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isFavorite: Boolean = false,
    val title: String? = null,
    val type: String? = null,
    val coverThumbPath: String? = null,
    val updatedAtEpochMs: Long,
)
