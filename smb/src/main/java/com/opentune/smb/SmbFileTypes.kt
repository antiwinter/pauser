package com.opentune.smb

fun isLikelyVideoFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".mkv") || lower.endsWith(".mp4") || lower.endsWith(".avi") ||
        lower.endsWith(".webm") || lower.endsWith(".m4v")
}
