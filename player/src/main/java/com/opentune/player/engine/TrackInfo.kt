package com.opentune.player.engine

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.opentune.storage.EntryStateKey

internal data class TrackInfo(
    val videoMime: String? = null,
    val videoDecoderName: String? = null,
    val audioMime: String? = null,
    val audioDecoderName: String? = null,
)

// MIME types come from onTracksChanged. Decoder names come from AnalyticsListener callbacks
// which fire when a decoder is initialized — this covers both the normal path and fallback
// retries (each retry re-initializes the decoder, firing again).
//
// Tracks that exist but are disabled (e.g., no decoder available) are still tracked so the
// OSD can show them with a "failed" indicator.
@UnstableApi
@Composable
internal fun rememberTrackInfo(
    exo: ExoPlayer,
    instanceKey: EntryStateKey,
    mainHandler: Handler,
): State<TrackInfo> {
    val state = remember(instanceKey) { mutableStateOf(TrackInfo()) }

    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                var vm: String? = null
                var am: String? = null
                for (group in tracks.groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val fmt = group.getTrackFormat(i)
                        when (group.type) {
                            C.TRACK_TYPE_VIDEO -> vm = fmt.sampleMimeType
                            C.TRACK_TYPE_AUDIO -> am = fmt.sampleMimeType
                        }
                        break // first track in group
                    }
                }
                mainHandler.post {
                    val current = state.value
                    val updated = current.copy(
                        videoMime = vm ?: current.videoMime,
                        audioMime = am ?: current.audioMime,
                    )
                    Log.d(
                        "TrackInfo",
                        "onTracksChanged videoMime=$vm audioMime=$am " +
                            "stored=${updated.videoMime}/${updated.audioMime} groups=${tracks.groups.size}"
                    )
                    state.value = updated
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
                state.value = state.value.copy(videoDecoderName = decoderName)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.d("TrackInfo", "onAudioDecoderInitialized decoderName=$decoderName")
                state.value = state.value.copy(audioDecoderName = decoderName)
            }

            // For passthrough audio (e.g., EAC3 via HDMI), no decoder is initialized.
            // Use onAudioEnabled to set a placeholder name so passthrough doesn't show as "failed".
            override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
                mainHandler.post {
                    val current = state.value
                    Log.d("TrackInfo", "onAudioEnabled audioMime=${current.audioMime} audioDecoderName=${current.audioDecoderName}")
                    // Set placeholder immediately - onTracksChanged fires right after and sets audioMime
                    if (current.audioDecoderName == null) {
                        Log.d("TrackInfo", "Setting passthrough placeholder")
                        state.value = current.copy(audioDecoderName = "passthrough")
                    }
                }
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
