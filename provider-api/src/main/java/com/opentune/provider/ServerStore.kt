package com.opentune.provider

import kotlinx.coroutines.flow.Flow

data class ServerRecord(
    val id: Long,
    val providerId: String,
    val displayName: String,
    val fieldsJson: String,
)

interface ServerStore {
    suspend fun insert(providerId: String, displayName: String, fieldsJson: String): Long

    suspend fun update(sourceId: Long, displayName: String, fieldsJson: String)

    suspend fun delete(sourceId: Long)

    fun observeByProvider(providerId: String): Flow<List<ServerRecord>>

    suspend fun get(sourceId: Long): ServerRecord?
}
