package com.opentune.player.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
