package com.opentune.app.drafts

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class DraftFile(
    val drafts: Map<String, Map<String, String>> = emptyMap(),
)

class AddServerDraftStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "add_server_drafts.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(providerId: String): Map<String, String> = mutex.withLock {
        readUnsafe().drafts[providerId] ?: emptyMap()
    }

    suspend fun save(providerId: String, values: Map<String, String>) {
        mutex.withLock {
            val cur = readUnsafe()
            val next = cur.drafts.toMutableMap()
            next[providerId] = values
            writeUnsafe(DraftFile(drafts = next))
        }
    }

    suspend fun clear(providerId: String) {
        mutex.withLock {
            val cur = readUnsafe()
            val next = cur.drafts.toMutableMap()
            next.remove(providerId)
            writeUnsafe(DraftFile(drafts = next))
        }
    }

    private fun readUnsafe(): DraftFile {
        if (!file.exists()) return DraftFile()
        return runCatching {
            json.decodeFromString(DraftFile.serializer(), file.readText())
        }.getOrElse { DraftFile() }
    }

    private fun writeUnsafe(data: DraftFile) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(DraftFile.serializer(), data))
    }
}
