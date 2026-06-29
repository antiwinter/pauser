package com.opentune.player.manager

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.opentune.player.engine.PlaybackSession
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Error classification helper
// ---------------------------------------------------------------------------

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
// Session extension: setTrackEnabled
// ---------------------------------------------------------------------------

/** Disables/enables a track type and re-prepares (used by decoder fallback recovery). */
@UnstableApi
fun PlaybackSession.setTrackEnabled(trackType: Int, enabled: Boolean) {
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(trackType, !enabled)
        .build()
    rebuildKeepingPosition()
}

// ---------------------------------------------------------------------------
// FallbackManager class
// ---------------------------------------------------------------------------

/**
 * Track-level fallback: video fails → audio-only, audio fails → video-only.
 * A permanent [Player.Listener] that classifies decoder errors and triggers the appropriate
 * fallback. Per-entry [AtomicBoolean] guards are reset in [onPrepare] (called by the session
 * on each new entry) so they don't leak across entries.
 */
@UnstableApi
internal class FallbackManager(
    private val session: PlaybackSession,
) : PlaybackManager {

    private val videoFailed = AtomicBoolean(false)
    private val audioFailed = AtomicBoolean(false)

    override fun onPrepare() {
        videoFailed.set(false)
        audioFailed.set(false)
    }

    override val listeners: List<Player.Listener> = listOf(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val ti = session.trackInfoFlow.value
            when {
                error.causeChainContains("MediaCodecVideoRenderer") -> onRendererFailure(
                    error, "MediaCodecVideoRenderer", C.TRACK_TYPE_VIDEO,
                    failed = videoFailed, otherFailed = audioFailed,
                    markErr = { session.updateTrackInfo { it.copy(videoDecoderStatus = "err") } },
                    mime = ti.videoMime,
                )
                error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") -> onRendererFailure(
                    error, "MediaCodecAudioRenderer", C.TRACK_TYPE_AUDIO,
                    failed = audioFailed, otherFailed = videoFailed,
                    markErr = { session.updateTrackInfo { it.copy(audioDecoderStatus = "err") } },
                    mime = ti.audioMime,
                )
                else -> {
                    Timber.e(error, "unhandled player error: code=${error.errorCode} msg=${error.message}")
                }
            }
        }
    })

    /**
     * Shared body for a decoder-renderer failure: marks the track errored, acquires the one-shot
     * per-entry fallback lock (bails if this side already failed or both sides have failed), then
     * disables the failed track type and continues with the other. [keyword] is used for the log.
     */
    private fun onRendererFailure(
        error: PlaybackException,
        keyword: String,
        trackType: Int,
        failed: AtomicBoolean,
        otherFailed: AtomicBoolean,
        markErr: () -> Unit,
        mime: String?,
    ) {
        markErr()
        if (!failed.compareAndSet(false, true)) {
            Timber.w("$keyword already failed; propagating error")
            return
        }
        if (otherFailed.get()) {
            Timber.w("both renderers failed; propagating error")
            return
        }
        Timber.w("$keyword decode failed — disabling track type $trackType. mime=$mime")
        session.setTrackEnabled(trackType, false)
    }
}
