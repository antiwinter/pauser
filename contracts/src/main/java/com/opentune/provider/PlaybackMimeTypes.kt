package com.opentune.provider

/**
 * Best-effort MIME hints for ExoPlayer from container / format tokens
 * (e.g. Emby `MediaSourceInfo.transcodingContainer` / `container`).
 * Returns null when unknown so the player can infer from the stream.
 */
object PlaybackMimeTypes {

    fun fromContainers(transcodingContainer: String?, container: String?): String? {
        val raw = transcodingContainer?.takeIf { it.isNotBlank() }
            ?: container?.takeIf { it.isNotBlank() }
            ?: return null
        return fromRawFormat(raw)
    }

    fun fromRawFormat(raw: String): String? = when (raw.lowercase()) {
        "m3u8", "hls" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "mkv", "matroska" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }
}
