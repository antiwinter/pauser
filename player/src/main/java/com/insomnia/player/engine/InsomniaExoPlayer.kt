package com.insomnia.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

@UnstableApi
object InsomniaExoPlayer {

    data class PlayerWithMeter(val player: ExoPlayer, val bandwidthMeter: DefaultBandwidthMeter)

    /**
     * ExoPlayer for provider-supplied [androidx.media3.exoplayer.source.MediaSource] instances
     * (each source bundles its own [androidx.media3.datasource.DataSource]).
     */
    @JvmStatic
    fun createForBundledSources(
        context: Context,
        preBufferMs: Int = 30 * 1000,
    ): PlayerWithMeter {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ (preBufferMs - 1_000).coerceAtLeast(1_000),
                /* preBufferMs = */ preBufferMs,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 200,
            )
            .build()
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val renderersFactory = InsomniaRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
        return PlayerWithMeter(player, bandwidthMeter)
    }
}
