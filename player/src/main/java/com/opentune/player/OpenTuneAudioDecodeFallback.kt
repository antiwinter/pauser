package com.opentune.player

import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.util.concurrent.atomic.AtomicBoolean

/** Single tag for generic player-side diagnostics (catalog + fallback). */
const val OPEN_TUNE_PLAYER_LOG = "OpenTunePlayer"

fun PlaybackException.isOpenTuneAudioDecodeFailure(): Boolean {
    val texts = sequence {
        yield(message)
        var t: Throwable? = cause
        while (t != null) {
            yield(t.message)
            t = t.cause
        }
    }
    return texts.any { it?.contains("MediaCodecAudioRenderer", ignoreCase = true) == true } ||
        texts.any { it?.contains("AudioSink", ignoreCase = true) == true }
}

/**
 * One-shot: on audio decode / sink failure, disable audio tracks and re-prepare with the same media.
 * Works for any progressive source. Call [ExoPlayer.addListener] with the return value;
 * remove the listener in [DisposableEffect] if the player instance outlives the composable.
 */
fun ExoPlayer.createAudioDecodeFallbackListener(
    logTag: String,
    mainHandler: Handler,
    media: AudioFallbackMedia,
    onAudioDisabled: () -> Unit,
): Player.Listener {
    val audioDecodeRetryTaken = AtomicBoolean(false)
    val exo = this
    return object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!error.isOpenTuneAudioDecodeFailure()) {
                Log.e(logTag, "onPlayerError code=${error.errorCode} msg=${error.message}", error)
                return
            }
            if (!audioDecodeRetryTaken.compareAndSet(false, true)) {
                Log.w(logTag, "Audio error after in-place retry; ignoring code=${error.errorCode}")
                return
            }
            Log.w(
                logTag,
                "Audio decode failed; disabling audio on same ExoPlayer. code=${error.errorCode}",
                error,
            )
            mainHandler.post {
                onAudioDisabled()
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                exo.stop()
                when (media) {
                    is AudioFallbackMedia.MediaSourcePayload -> exo.setMediaSource(media.source)
                    is AudioFallbackMedia.MediaItemPayload -> exo.setMediaItem(media.item)
                }
                exo.playWhenReady = true
                exo.prepare()
                Log.d(logTag, "in-place audio-off prepare issued")
            }
        }
    }
}

sealed class AudioFallbackMedia {
    data class MediaSourcePayload(val source: MediaSource) : AudioFallbackMedia()
    data class MediaItemPayload(val item: MediaItem) : AudioFallbackMedia()
}
