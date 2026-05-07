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
    primaryKeys = ["sourceId", "itemId"],
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
    /**
     * Tri-state cover cache path:
     * - null  = not yet attempted
     * - [COVER_FAILED] = extraction failed, do not retry
     * - any other string = absolute path to cached thumbnail
     */
    val coverCachePath: String? = null,
    /** Last-chosen subtitle track ID for this item; null = no subtitle selected. */
    val selectedSubtitleTrackId: String? = null,
    /** Last-chosen audio track ID for this item; null = auto selection. */
    val selectedAudioTrackId: String? = null,
    val updatedAtEpochMs: Long,
) {
    companion object {
        const val COVER_FAILED = "failed"
    }
}
