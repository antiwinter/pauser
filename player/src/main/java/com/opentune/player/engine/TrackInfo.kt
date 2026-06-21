package com.opentune.player.engine

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Tracks

internal data class TrackInfo(
    val videoMime: String? = null,
    val videoDecoderStatus: String = "n/a",
    val audioMime: String? = null,
    val audioDecoderStatus: String = "n/a",
    val isHdrCapable: Boolean = false,
    val videoBitrate: Int? = null,
)

// TrackInfo is owned and populated by PlaybackSession, whose listeners are registered once for the
// whole ExoPlayer lifetime and reset on prepare(). This is deliberate: decoder-name callbacks
// (onVideo/AudioDecoderInitialized, onAudioEnabled) are ONE-SHOT — they fire once when a decoder
// starts. A Compose-scoped listener that mounts after the decoder has already initialized would
// miss them forever and be stuck at "n/a". Anchoring to the player lifecycle guarantees capture.
//
// Two independent facts, never stored in the same field:
//   *Mime          — the SELECTED track's MIME (null = no such track). From onTracksChanged.
//   *DecoderStatus — what is decoding that track. Purely decoder-sourced; onTracksChanged
//                    never touches it, so there are no cross-callback guards to get wrong.
//
// decoderStatus values & how InfoOverlay renders them:
//   "n/a"         — no decoder resolved yet / none available     → "codec[n/a]"
//   "c2" / "OMX"  — onVideo/AudioDecoderInitialized              → "codec[c2]"
//   "passthrough" — onAudioEnabled w/ no decoder (HDMI ARC etc.) → "codec"  (bracket hidden)
//   "err"         — onPlayerError                                → "codec[err]"

/** Extracts decoder prefix (e.g., "c2", "OMX") from full decoder name. */
internal fun simplifyDecoderName(decoderName: String): String = when {
    decoderName.startsWith("c2.") -> "c2"
    decoderName.startsWith("OMX.") -> "OMX"
    else -> decoderName.substringBefore('.').takeIf { it.isNotEmpty() } ?: decoderName
}

/** The Format of the currently selected track of [trackType], or null if none is selected. */
internal fun Tracks.selectedFormat(trackType: @C.TrackType Int): Format? {
    for (group in groups) {
        if (group.type != trackType) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
    }
    return null
}

/** True when the video Format carries HDR (Dolby Vision or an HDR transfer function). */
internal fun isHdrFormat(videoFormat: Format?): Boolean =
    videoFormat?.sampleMimeType == "video/dolby-vision" ||
        (videoFormat?.colorInfo != null && ColorInfo.isTransferHdr(videoFormat.colorInfo))

/** Encoded bitrate from a Format, preferring peak then average; null if unknown. */
internal fun formatBitrate(format: Format?): Int? {
    if (format == null) return null
    return format.peakBitrate.takeIf { it > 0 } ?: format.averageBitrate.takeIf { it > 0 }
}
