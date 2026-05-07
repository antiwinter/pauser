package com.opentune.player.subtitle

import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.opentune.provider.SubtitleTrack

internal fun buildTrackLabel(
    track: SubtitleTrack,
    exoLabel: String? = null,
    exoLang: String? = null,
): String {
    val base = exoLabel?.takeIf { it.isNotBlank() } ?: track.label
    val langTag = languageDisplayName(exoLang ?: track.language)
    val flags = buildString {
        if (track.isDefault) append(" ●")
        if (track.isForced) append(" (Forced)")
    }
    return "[$langTag] ${track.trackId} $base$flags"
}

internal fun languageDisplayName(lang: String?): String = when (lang?.lowercase()?.take(3)) {
    "zh", "chi", "zho" -> "Chinese"
    "en", "eng" -> "English"
    "ja", "jpn" -> "Japanese"
    "ko", "kor" -> "Korean"
    "fr", "fre", "fra" -> "French"
    "de", "ger", "deu" -> "German"
    "es", "spa" -> "Spanish"
    "it", "ita" -> "Italian"
    "pt", "por" -> "Portuguese"
    "ru", "rus" -> "Russian"
    "ar", "ara" -> "Arabic"
    "th", "tha" -> "Thai"
    "vi", "vie" -> "Vietnamese"
    null, "und", "" -> "Unknown"
    else -> lang ?: "Unknown"
}

@UnstableApi
internal fun buildExoTrackLabel(group: Tracks.Group, fallbackIndex: Int): String {
    if (group.length == 0) return "Track ${fallbackIndex + 1}"
    val fmt = group.getTrackFormat(0)
    return when {
        !fmt.label.isNullOrBlank() -> fmt.label!!
        !fmt.language.isNullOrBlank() -> fmt.language!!
        else -> "Track ${fallbackIndex + 1}"
    }
}

internal fun subtitleMimeType(ref: String): String {
    val path = ref.substringBefore('?')
    return when (path.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
