package com.opentune.provider

/** Stable row key for [PlaybackProgressEntity] / [ProgressStore]. */
fun progressPersistenceKey(providerId: String, sourceId: Long, itemId: String): String =
    "${providerId}|${sourceId}|$itemId"

interface ProgressStore {
    suspend fun getPositionMs(providerId: String, sourceId: Long, itemId: String): Long?

    suspend fun upsert(providerId: String, sourceId: Long, itemId: String, positionMs: Long)
}
