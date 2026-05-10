package com.opentune.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.opentune.storage.DataStoreAppConfigStore

@UnstableApi
object OpenTuneExoPlayer {

    /**
     * Prefers hardware/vendor decoders over AOSP software (e.g. [OMX.google.aac.decoder]), which
     * often misbehaves or fails on Android TV even when the stream is marked decoder-compatible.
     */
    private val preferHardwareDecodersFirst = MediaCodecSelector { mimeType, secure, tunneling ->
        val infos = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        if (infos.size <= 1) return@MediaCodecSelector infos
        infos.sortedWith(
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
    }

    data class PlayerWithMeter(val player: ExoPlayer, val bandwidthMeter: DefaultBandwidthMeter)

    /**
     * ExoPlayer for provider-supplied [androidx.media3.exoplayer.source.MediaSource] instances
     * (each source bundles its own [androidx.media3.datasource.DataSource]).
     *
     * [bandwidthMeter] is owned by the player after [ExoPlayer.Builder.setBandwidthMeter]; it is
     * torn down automatically when [ExoPlayer.release] is called — no separate disposal needed.
     */
    fun createForBundledSources(context: Context, preBufferMs: Int = DataStoreAppConfigStore.DEFAULT_PRE_BUFFER_MS): PlayerWithMeter {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                preBufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(preferHardwareDecodersFirst)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
        return PlayerWithMeter(player, bandwidthMeter)
    }
}
