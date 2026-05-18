package com.opentune.player.engine

import android.os.Handler
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
import com.opentune.storage.MediaStateKey

internal data class TrackInfo(
    val videoMime: String? = null,
    val videoDecoderName: String? = null,
    val audioMime: String? = null,
    val audioDecoderName: String? = null,
)

// MIME types come from onTracksChanged. Decoder names come from AnalyticsListener callbacks
// which fire when a decoder is initialized — this covers both the normal path and fallback
// retries (each retry re-initializes the decoder, firing again).
@UnstableApi
@Composable
internal fun rememberTrackInfo(
    exo: ExoPlayer,
    instanceKey: MediaStateKey,
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
                            // Only update when a track is actively selected; preserve last-known
                            // value when the track is disabled so the OSD can still show it.
                            C.TRACK_TYPE_VIDEO -> vm = fmt.sampleMimeType
                            C.TRACK_TYPE_AUDIO -> am = fmt.sampleMimeType
                        }
                        break
                    }
                }
                mainHandler.post {
                    val current = state.value
                    state.value = current.copy(
                        videoMime = vm ?: current.videoMime,
                        audioMime = am ?: current.audioMime,
                    )
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
                state.value = state.value.copy(audioDecoderName = decoderName)
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
