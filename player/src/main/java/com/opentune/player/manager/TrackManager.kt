package com.opentune.player.manager

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.opentune.player.engine.PlaybackSession

// ---------------------------------------------------------------------------
// TrackInfo data + helpers
// ---------------------------------------------------------------------------

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
//   "err"         — onPlayerError (FallbackManager)              → "codec[err]"

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
internal fun bitrateOf(format: Format?): Int? {
    if (format == null) return null
    return format.peakBitrate.takeIf { it > 0 } ?: format.averageBitrate.takeIf { it > 0 }
}

// ---------------------------------------------------------------------------
// TrackManager class
// ---------------------------------------------------------------------------

private const val TRACK_LOG = "TrackInfo"

/**
 * Owns the [TrackInfo] flow and the ExoPlayer listeners that populate it. Anchored to the player
 * lifetime — attach once and never re-attach — so it never misses the one-shot decoder-init
 * callbacks described above.
 *
 * Error handling is deliberately NOT here: decoder errors are owned by [FallbackManager], which
 * classifies video vs. audio for fallback and writes the 'err' status via [PlaybackSession.updateTrackInfo].
 */
@UnstableApi
internal class TrackManager(
    private val session: PlaybackSession,
) : PlaybackManager {

    override fun onPrepare() {
        session.tracks = Tracks.EMPTY
        session.updateTrackInfo { TrackInfo() }
    }

    override val listeners: List<Player.Listener> = listOf(object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            session.tracks = tracks
            val videoFormat = tracks.selectedFormat(C.TRACK_TYPE_VIDEO)
            val audioFormat = tracks.selectedFormat(C.TRACK_TYPE_AUDIO)
            session.updateTrackInfo {
                it.copy(
                    videoMime = videoFormat?.sampleMimeType,
                    audioMime = audioFormat?.sampleMimeType,
                    isHdrCapable = isHdrFormat(videoFormat),
                    videoBitrate = bitrateOf(videoFormat),
                )
            }
            Log.d(
                TRACK_LOG,
                "tracks v=${videoFormat?.sampleMimeType} a=${audioFormat?.sampleMimeType} " +
                    "hdr=${isHdrFormat(videoFormat)} bitrate=${bitrateOf(videoFormat)} groups=${tracks.groups.size}"
            )
        }

        // The player is the source of truth for speed (set via exo.playbackParameters); mirror it
        // into persisted state here so every speed change — menu pick or restore — is captured once.
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            session.updateSpeed(parameters.speed)
        }
    })

    override val analyticsListeners: List<AnalyticsListener> = listOf(object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.d(TRACK_LOG, "videoDecoder=$decoderName")
            session.updateTrackInfo { it.copy(videoDecoderStatus = simplifyDecoderName(decoderName)) }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.d(TRACK_LOG, "audioDecoder=$decoderName")
            session.updateTrackInfo { it.copy(audioDecoderStatus = simplifyDecoderName(decoderName)) }
        }

        // Fires for both decoded and passthrough audio. Passthrough never gets a decoder-init,
        // so claim the field only while still unresolved ("n/a"); a real decoder-init wins either
        // way (it overrides "passthrough", or this no-ops when it already ran).
        override fun onAudioEnabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters,
        ) {
            session.updateTrackInfo {
                if (it.audioDecoderStatus == "n/a") it.copy(audioDecoderStatus = "passthrough") else it
            }
        }
    })
}
