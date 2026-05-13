package com.opentune.storage.thumb

import java.io.File
import java.security.MessageDigest

/**
 * Simple disk cache for extracted cover thumbnail bytes.
 * Files are stored under [cacheDir]/<sourceId>/<sha256(itemId).take(16)>
 * so that [deleteBySource] can efficiently wipe all thumbnails for a removed server.
 */
class ThumbnailDiskCache(private val cacheDir: File) {

    private fun sourceDir(sourceId: String): File = File(cacheDir, sourceId)

    private fun keyFile(sourceId: String, itemId: String): File {
        val hash = sha256(itemId).take(16)
        return File(sourceDir(sourceId), hash)
    }

    /** Writes [bytes] to disk and returns the absolute path of the cached file. */
    fun put(sourceId: String, itemId: String, bytes: ByteArray): String {
        val f = keyFile(sourceId, itemId)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** Returns the absolute path if already cached, otherwise null. */
    fun get(sourceId: String, itemId: String): String? {
        val f = keyFile(sourceId, itemId)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /** Deletes all cached thumbnails for [sourceId]. */
    fun deleteBySource(sourceId: String) {
        sourceDir(sourceId).deleteRecursively()
    }

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
