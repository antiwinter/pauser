package com.insomnia.content.contract

/**
 * Central filename type detection.
 * Used by routing layer to classify entries with type "Unknown" from providers
 * that don't perform their own detection (e.g. SMB).
 */
object FilenameDetector {
    private val VIDEO_EXTS = setOf(
        ".mkv", ".mp4", ".avi", ".webm", ".m4v", ".mov", ".wmv", ".flv", ".ts", ".m2ts",
    )
    private val AUDIO_EXTS = setOf(
        ".mp3", ".flac", ".aac", ".ogg", ".wav", ".wma", ".m4a", ".opus", ".alac",
    )
    private val IMAGE_EXTS = setOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tiff",
    )

    fun detectType(filename: String): String = when {
        filename.endsWithAny(VIDEO_EXTS) -> "Video"
        filename.endsWithAny(AUDIO_EXTS) -> "Audio"
        filename.endsWithAny(IMAGE_EXTS) -> "Image"
        else -> "Unknown"
    }

    private fun String.endsWithAny(exts: Set<String>): Boolean =
        exts.any { lowercase().endsWith(it) }
}
