package com.opentune.player.engine

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

internal data class TrackInfo(
    val videoMime: String? = null,
    val videoDecoderStatus: String = "",
    val audioMime: String? = null,
    val audioDecoderStatus: String = "",
    val isHdrCapable: Boolean = false,
)

// MIME types and availability come from onTracksChanged via lookFor().
// Decoder names come from AnalyticsListener callbacks (fire when a decoder initializes).
//
// decoderStatus values:
//   ""      — selected, decoder not yet initialized → shows "codec"
//   "c2"    — decoder initialized                  → shows "codec[c2]"
//   "n/a"   — track exists but not selected         → shows "codec[n/a]"
//   "err"   — runtime decode error                  → shows "codec[err]"
//
// IMPORTANT: onTracksChanged can fire transiently (e.g. mid-track-switch) with no
// selected track. The "n/a" assignment MUST be guarded by isEmpty() so a running
// decoder's status is never overwritten. See git history for recurring bug pattern.

/** Extracts decoder prefix (e.g., "c2", "OMX") from full decoder name. */
private fun simplifyDecoderName(decoderName: String): String {
    return when {
        decoderName.startsWith("c2.") -> "c2"
        decoderName.startsWith("OMX.") -> "OMX"
        else -> decoderName.substringBefore('.').takeIf { it.isNotEmpty() } ?: decoderName
    }
}
@UnstableApi
@Composable
internal fun rememberTrackInfo(
    exo: ExoPlayer,
    instanceKey: String,
    mainHandler: Handler,
): State<TrackInfo> {
    val state = remember(instanceKey) { mutableStateOf(TrackInfo()) }

    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                fun lookFor(trackType: @C.TrackType Int): Pair<String?, Boolean> {
                    var mime: String? = null
                    for (group in tracks.groups) {
                        if (group.type != trackType) continue
                        for (i in 0 until group.length) {
                            if(mime == null || group.isTrackSelected(i))
                                mime = group.getTrackFormat(0).sampleMimeType
                            if (group.isTrackSelected(i))
                                return mime to false
                        }
                    }
                    return mime to true
                }

                fun lookForFormat(trackType: @C.TrackType Int): Format? {
                    for (group in tracks.groups) {
                        if (group.type != trackType) continue
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i))
                                return group.getTrackFormat(i)
                        }
                    }
                    return null
                }

                val (vm, vNA) = lookFor(C.TRACK_TYPE_VIDEO)
                val (am, aNA) = lookFor(C.TRACK_TYPE_AUDIO)

                val videoFormat = lookForFormat(C.TRACK_TYPE_VIDEO)
                val hdrContent = videoFormat?.sampleMimeType == "video/dolby-vision"
                    || (videoFormat?.colorInfo != null && ColorInfo.isTransferHdr(videoFormat.colorInfo))

                mainHandler.post {
                    val current = state.value
                    val updated = current.copy(
                        videoMime = vm,
                        videoDecoderStatus = if (vNA && current.videoDecoderStatus.isEmpty()) "n/a" else current.videoDecoderStatus,
                        audioMime = am,
                        audioDecoderStatus = if (aNA && current.audioDecoderStatus.isEmpty()) "n/a" else current.audioDecoderStatus,
                        isHdrCapable = hdrContent,
                    )
                    Log.d(
                        "TrackInfo",
                        "onTracksChanged videoMime=$vm audioMime=$am " +
                            "vNA=$vNA aNA=$aNA hdrContent=$hdrContent " +
                            "stored=${updated.videoMime}/${updated.audioMime} groups=${tracks.groups.size}"
                    )
                    state.value = updated
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                mainHandler.post {
                    val current = state.value
                    when {
                        error.causeChainContains("MediaCodecVideoRenderer") -> {
                            Log.w("TrackInfo", "Video decode failed")
                            state.value = current.copy(videoDecoderStatus = "err")
                        }
                        error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") -> {
                            Log.w("TrackInfo", "Audio decode failed")
                            state.value = current.copy(audioDecoderStatus = "err")
                        }
                    }
                }
            }
        }

        val analyticsListener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                state.value = state.value.copy(videoDecoderStatus = simplifyDecoderName(decoderName))
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.d("TrackInfo", "onAudioDecoderInitialized decoderName=$decoderName")
                state.value = state.value.copy(audioDecoderStatus = simplifyDecoderName(decoderName))
            }
        }

        exo.addListener(listener)
        exo.addAnalyticsListener(analyticsListener)
        onDispose {
            exo.removeListener(listener)
            exo.removeAnalyticsListener(analyticsListener)
        }
    }

    return state
}
