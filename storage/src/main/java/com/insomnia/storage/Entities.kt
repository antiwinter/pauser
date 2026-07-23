package com.insomnia.storage

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
    primaryKeys = ["endpointId", "itemRef"],
)
data class EntryStateEntity(
    val endpointId: String,
    val itemRef: String,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isFavorite: Boolean = false,
    val title: String? = null,
    val type: String? = null,
    /** Last-chosen subtitle track ID for this item; null = no subtitle selected. */
    val selectedSubtitleTrackId: String? = null,
    /** Last-chosen audio track ID for this item; null = auto selection. */
    val selectedAudioTrackId: String? = null,
    /** Cached cover URL; populated by remote recent fetches so local recents can render before playback. */
    val cover: String? = null,
    /** Cached etag from the remote provider; allows invalidation. */
    val etag: String? = null,
    val updatedAtEpochMs: Long,
)
