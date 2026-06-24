package com.opentune.player.manager.subtitle

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.opentune.player.PlaybackSpec
import com.opentune.player.SubtitleTrack

/** Maps an external subtitle ref (URL/path) to a media3 MIME type by file extension. */
internal fun subtitleMimeType(ref: String): String {
    val path = ref.substringBefore('?')
    return when (path.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/** The saved subtitle track for [savedId] within the active source, or null if none/unmatched. */
internal fun PlaybackSpec.savedSubtitleTrack(savedId: String?): SubtitleTrack? {
    if (savedId == null) return null
    return sources[state.sourceIndex].subtitleTracks.find { it.trackId == savedId }
}

/**
 * Builds a sidecar [MediaItem.SubtitleConfiguration] for an external subtitle track, or null for an
 * embedded track. Language is intentionally left undetermined so the player auto-selects the track
 * via `setSelectUndeterminedTextLanguage(true)` — the same selection the sidecar reselect path uses.
 */
internal fun SubtitleTrack.toSidecarConfig(): MediaItem.SubtitleConfiguration? {
    val ref = externalRef ?: return null
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(ref))
        .setMimeType(subtitleMimeType(ref))
        .build()
}

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
