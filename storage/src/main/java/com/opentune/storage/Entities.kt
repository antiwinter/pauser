package com.opentune.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey val endpointId: String,
    val protocol: String,
    val displayName: String,
    val fieldsJson: String,
    val proxyId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "proxies")
data class ProxyEntity(
    @PrimaryKey val id: String,
    val proxyType: String,
    val displayName: String,
    val fieldsJson: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "media_state",
    primaryKeys = ["endpointId", "itemId"],
)
data class EntryStateEntity(
    val protocol: String,
    val endpointId: String,
    val itemId: String,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isFavorite: Boolean = false,
    val title: String? = null,
    val type: String? = null,
    /** Last-chosen subtitle track ID for this item; null = no subtitle selected. */
    val selectedSubtitleTrackId: String? = null,
    /** Last-chosen audio track ID for this item; null = auto selection. */
    val selectedAudioTrackId: String? = null,
    val updatedAtEpochMs: Long,
)
