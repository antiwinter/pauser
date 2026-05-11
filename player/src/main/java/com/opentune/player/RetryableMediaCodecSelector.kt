package com.opentune.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * A [MediaCodecSelector] that:
 * - Sorts hardware/vendor decoders before AOSP software decoders (same policy as the previous
 *   static selector).
 * - Tracks which decoder was last dispensed for each MIME type.
 * - Allows a runtime decode failure to blacklist the current decoder ([markFailed]) so the next
 *   [ExoPlayer.prepare] picks a different one.
 * - [isExhausted] returns true once every real decoder for a MIME type has been blacklisted,
 *   so the caller can disable the track rather than attempt another retry.
 *
 * Thread-safe: the selector lambda may be called from any thread; state is held in
 * [ConcurrentHashMap]s. [markFailed], [isExhausted], and [currentDecoderName] are also safe
 * to call from any thread.
 */
@UnstableApi
internal class RetryableMediaCodecSelector {

    /** Decoder names that failed at runtime, keyed by MIME type. */
    private val failedDecoders = ConcurrentHashMap<String, MutableSet<String>>()

    /** Last decoder name returned as the first candidate for each MIME type. */
    private val dispensed = ConcurrentHashMap<String, String>()

    /** Total real decoder count per MIME type, captured on first selector query. */
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

    /** Mark the decoder most recently dispensed for [mimeType] as failed. No-op if none known. */
    fun markFailed(mimeType: String) {
        val name = dispensed[mimeType] ?: return
        failedDecoders.getOrPut(mimeType) { ConcurrentHashMap.newKeySet() }.add(name)
    }

    /** Returns true when every known decoder for [mimeType] has been marked failed. */
    fun isExhausted(mimeType: String): Boolean {
        val total = totalCounts[mimeType] ?: return false
        return (failedDecoders[mimeType]?.size ?: 0) >= total
    }

    /** Returns the decoder name most recently dispensed for [mimeType], or null if never queried. */
    fun currentDecoderName(mimeType: String): String? = dispensed[mimeType]

    /** Clears all failure history and dispensed state (call when starting a new item). */
    fun reset() {
        failedDecoders.clear()
        dispensed.clear()
        totalCounts.clear()
    }

    companion object {
        /** Value stored as the decoder name in OSD state when all decoders are exhausted. */
        const val NULL_DECODER_NAME = "opentune.null"
    }
}
