package com.opentune.content.contract

object PlaybackMimeTypes {
    /** Detect MIME type from URL file extension (ignoring query string). */
    fun fromUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            else -> null
        }
    }
}
