package com.opentune.player.engine

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

fun interface FormatPatch {
    fun patch(format: Format): Format
}

@UnstableApi
internal class OpenTuneRenderersFactory(
    context: Context,
    private val audioPatches: List<FormatPatch> = listOf(AacMainToLcPatch),
) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        out.add(
            PatchingAudioRenderer(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink,
                audioPatches,
            )
        )
    }
}

@UnstableApi
private class PatchingAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val patches: List<FormatPatch>,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxInputSize: Int,
        codecOperatingRate: Float,
    ): MediaFormat {
        val patched = patches.fold(format) { f, patch -> patch.patch(f) }
        return super.getMediaFormat(patched, codecMimeType, codecMaxInputSize, codecOperatingRate)
    }
}

// Rewrites AAC Main Profile (audioObjectType=1) CSD to AAC-LC (audioObjectType=2).
// Android decoders reject AOT=1 but handle AOT=2 correctly for Main Profile content.
// AudioSpecificConfig byte0 = [AOT(5 bits)][sampleRateIndex high 3 bits].
private object AacMainToLcPatch : FormatPatch {
    override fun patch(format: Format): Format {
        val csd = format.initializationData.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return format
        val byte0 = csd[0].toInt() and 0xFF
        val audioObjectType = byte0 ushr 3
        if (audioObjectType != 1) return format

        val patched = csd.copyOf()
        patched[0] = ((2 shl 3) or (byte0 and 0x07)).toByte()

        val newInitData = format.initializationData.toMutableList().also { it[0] = patched }
        return format.buildUpon().setInitializationData(newInitData).build()
    }
}
