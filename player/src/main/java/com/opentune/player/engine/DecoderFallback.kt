package com.opentune.player.engine

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.opentune.player.PlaybackSpec
import com.opentune.storage.EntryStateKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Set to true to re-enable the codec-retry decoder fallback path.
 *
 * Background: [RetryableMediaCodecSelector] was designed to work around cases where a codec
 * declares support for a format but fails at runtime. The actual fix was an AAC-level patch;
 * if a codec declares support it will most likely honour it, so the retry never triggered in
 * practice. Disabled to keep the engine lean. Left here in case a new device class proves
 * otherwise.
 */
internal const val DECODER_FALLBACK_ENABLED = false

private const val FALLBACK_LOG = "OpenTunePlayer"

// ---------------------------------------------------------------------------
// RetryableMediaCodecSelector
// ---------------------------------------------------------------------------

/**
 * A [MediaCodecSelector] that:
 * - Sorts hardware/vendor decoders before AOSP software decoders.
 * - Tracks which decoder was last dispensed for each MIME type.
 * - Allows a runtime decode failure to blacklist the current decoder ([markFailed]) so the next
 *   [ExoPlayer.prepare] picks a different one.
 * - [isExhausted] returns true once every real decoder for a MIME type has been blacklisted,
 *   so the caller can disable the track rather than attempt another retry.
 *
 * Thread-safe: the selector lambda may be called from any thread.
 */
@UnstableApi
internal class RetryableMediaCodecSelector {

    private val failedDecoders = ConcurrentHashMap<String, MutableSet<String>>()
    private val dispensed = ConcurrentHashMap<String, String>()
    private val totalCounts = ConcurrentHashMap<String, Int>()

    val selector = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        totalCounts[mimeType] = all.size
        val failed = failedDecoders[mimeType] ?: emptySet<String>()
        val available = all.filter { it.name !in failed }

        val result = if (available.size <= 1) available else available.sortedWith(
            compareBy(
                { it.softwareOnly },
                { info ->
                    when {
                        info.name.startsWith("OMX.google") -> 1
                        info.name.startsWith("c2.android") -> 1
                        else -> 0
                    }
                },
            ),
        )

        result.firstOrNull()?.let { dispensed[mimeType] = it.name }
        result
    }

    fun markFailed(mimeType: String) {
        val name = dispensed[mimeType] ?: return
        failedDecoders.getOrPut(mimeType) { ConcurrentHashMap.newKeySet() }.add(name)
    }

    fun isExhausted(mimeType: String): Boolean {
        val total = totalCounts[mimeType] ?: return false
        return (failedDecoders[mimeType]?.size ?: 0) >= total
    }

    fun currentDecoderName(mimeType: String): String? = dispensed[mimeType]
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

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
// Composable API — two-phase so the selector can be passed to ExoPlayer.Builder
// ---------------------------------------------------------------------------

/**
 * Phase 1 — returns the [RetryableMediaCodecSelector] to use when constructing [ExoPlayer].
 * Returns null (and is a no-op) when [DECODER_FALLBACK_ENABLED] is false.
 */
@UnstableApi
@Composable
internal fun rememberFallbackCodecSelector(
    instanceKey: EntryStateKey,
    preBufferMs: Int,
): RetryableMediaCodecSelector? {
    if (!DECODER_FALLBACK_ENABLED) return null
    return remember(instanceKey, preBufferMs) { RetryableMediaCodecSelector() }
}

/**
 * Phase 2 — registers the [Player.Listener] that retries with the next decoder on
 * [Player.Listener.onPlayerError]. No-op when [DECODER_FALLBACK_ENABLED] is false or
 * [selector] is null.
 *
 * Decoder names are updated via [AnalyticsListener] in [PlaybackEngine] on every decoder
 * initialization (including retries), so [FallbackEffect] only needs to drive the re-prepare
 * cycle; it does not touch [TrackInfo.videoDecoderName] / [TrackInfo.audioDecoderName].
 */
@UnstableApi
@Composable
internal fun FallbackEffect(
    exo: ExoPlayer,
    instanceKey: EntryStateKey,
    selector: RetryableMediaCodecSelector?,
    specState: State<PlaybackSpec>,
    trackInfoState: State<TrackInfo>,
    mainHandler: Handler,
    context: Context,
) {
    if (!DECODER_FALLBACK_ENABLED || selector == null) return

    DisposableEffect(exo, instanceKey) {
        val audioGate = AtomicBoolean(false)
        val videoGate = AtomicBoolean(false)

        fun handleDecoderError(
            gate: AtomicBoolean,
            trackType: Int,
            label: String,
            mime: String?,
            error: PlaybackException,
        ) {
            if (!gate.compareAndSet(false, true)) {
                Log.w(FALLBACK_LOG, "$label error during retry; ignoring code=${error.errorCode}")
                return
            }
            if (mime == null) {
                Log.w(FALLBACK_LOG, "$label decode error but mime unknown; ignoring")
                gate.set(false)
                return
            }
            val isRealDecoderFailure = error.causeChainContains("MediaCodec\$CodecException")
            if (!isRealDecoderFailure) {
                // Not a codec failure — rotating decoders won't help. Leave gate set so this
                // path is not retried, and let ExoPlayer surface the error normally.
                Log.w(FALLBACK_LOG, "$label error is not a MediaCodec failure; skipping decoder rotation code=${error.errorCode}")
                return
            }
            selector.markFailed(mime)
            val isExhausted = selector.isExhausted(mime)
            Log.w(
                FALLBACK_LOG,
                "$label decode failed; ${if (isExhausted) "all decoders exhausted" else "retrying next decoder"}. code=${error.errorCode}",
                error,
            )
            mainHandler.post {
                exo.stop()
                if (isExhausted) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                } else {
                    gate.set(false)
                }
                exo.setMediaSource(specState.value.toMediaSource(context))
                exo.playWhenReady = true
                exo.prepare()
                Log.d(FALLBACK_LOG, "in-place $label ${if (isExhausted) "disabled" else "decoder-swap"} prepare issued")
            }
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val ti = trackInfoState.value
                when {
                    error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") ->
                        handleDecoderError(audioGate, C.TRACK_TYPE_AUDIO, "audio", ti.audioMime, error)
                    error.causeChainContains("MediaCodecVideoRenderer") ->
                        handleDecoderError(videoGate, C.TRACK_TYPE_VIDEO, "video", ti.videoMime, error)
                    else -> Log.e(FALLBACK_LOG, "onPlayerError unhandled code=${error.errorCode} msg=${error.message}", error)
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
}
