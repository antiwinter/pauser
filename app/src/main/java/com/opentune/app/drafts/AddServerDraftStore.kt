package com.opentune.app.drafts

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EmbyAddDraft(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
)

@Serializable
data class SmbAddDraft(
    val displayName: String = "",
    val host: String = "",
    val shareName: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
)

@Serializable
private data class DraftFile(
    val emby: EmbyAddDraft? = null,
    val smb: SmbAddDraft? = null,
)

class AddServerDraftStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "add_server_drafts.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun loadEmby(): EmbyAddDraft? = mutex.withLock { readUnsafe().emby }

    suspend fun loadSmb(): SmbAddDraft? = mutex.withLock { readUnsafe().smb }

    suspend fun saveEmby(draft: EmbyAddDraft) {
        mutex.withLock {
            val cur = readUnsafe()
            writeUnsafe(DraftFile(emby = draft, smb = cur.smb))
        }
    }

    suspend fun saveSmb(draft: SmbAddDraft) {
        mutex.withLock {
            val cur = readUnsafe()
            writeUnsafe(DraftFile(emby = cur.emby, smb = draft))
        }
    }

    suspend fun clearEmby() {
        mutex.withLock {
            val cur = readUnsafe()
            writeUnsafe(DraftFile(emby = null, smb = cur.smb))
        }
    }

    suspend fun clearSmb() {
        mutex.withLock {
            val cur = readUnsafe()
            writeUnsafe(DraftFile(emby = cur.emby, smb = null))
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
