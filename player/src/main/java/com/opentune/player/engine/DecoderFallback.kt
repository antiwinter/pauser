package com.opentune.player.engine

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlaybackSpec
import java.util.concurrent.atomic.AtomicBoolean

/** Returns true when any keyword appears in the message, class name, or cause chain. */
internal fun PlaybackException.causeChainContains(vararg keywords: String): Boolean {
    var t: Throwable? = this
    while (t != null) {
        val msg = t.message ?: ""
        val className = t.javaClass.name
        val simpleName = t.javaClass.simpleName
        if (keywords.any {
                msg.contains(it, ignoreCase = true) ||
                    className.contains(it, ignoreCase = true) ||
                    simpleName.contains(it, ignoreCase = true)
            }) return true
        t = t.cause
    }
    return false
}

// ---------------------------------------------------------------------------
// Track-level fallback: video fails → audio-only, audio fails → video-only
// ---------------------------------------------------------------------------

private const val FALLBACK_LOG = "TrackFallback"

@UnstableApi
@Composable
internal fun TrackFallbackEffect(
    exo: ExoPlayer,
    instanceKey: String,
    specState: State<PlaybackSpec>,
    trackInfoState: State<TrackInfo>,
    mainHandler: Handler,
    context: Context,
    session: PlaybackSession,
) {
    DisposableEffect(exo, instanceKey) {
        val videoFailed = AtomicBoolean(false)
        val audioFailed = AtomicBoolean(false)

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val ti = trackInfoState.value
                when {
                    error.causeChainContains("MediaCodecVideoRenderer") -> {
                        if (!videoFailed.compareAndSet(false, true)) {
                            Log.w(FALLBACK_LOG, "video already failed; propagating error")
                            return
                        }
                        if (audioFailed.get()) {
                            Log.w(FALLBACK_LOG, "both video and audio failed; propagating error")
                            return
                        }
                        Log.w(FALLBACK_LOG, "video decode failed — disabling video track, continuing audio-only. mime=${ti.videoMime}")
                        mainHandler.post {
                            exo.stop()
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                                .build()
                            exo.setMediaSource(specState.value.toMediaSource(context, session.activeSidecarSubtitle()))
                            exo.playWhenReady = true
                            exo.prepare()
                        }
                    }

                    error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") -> {
                        if (!audioFailed.compareAndSet(false, true)) {
                            Log.w(FALLBACK_LOG, "audio already failed; propagating error")
                            return
                        }
                        if (videoFailed.get()) {
                            Log.w(FALLBACK_LOG, "both audio and video failed; propagating error")
                            return
                        }
                        Log.w(FALLBACK_LOG, "audio decode failed — disabling audio track, continuing video-only. mime=${ti.audioMime}")
                        mainHandler.post {
                            exo.stop()
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build()
                            exo.setMediaSource(specState.value.toMediaSource(context, session.activeSidecarSubtitle()))
                            exo.playWhenReady = true
                            exo.prepare()
                        }
                    }

                    else -> {
                        Log.e(FALLBACK_LOG, "unhandled player error: code=${error.errorCode} msg=${error.message}", error)
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
}
