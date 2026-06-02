package com.opentune.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.opentune.storage.AppPrefsStore

@UnstableApi
object OpenTuneExoPlayer {

    data class PlayerWithMeter(val player: ExoPlayer, val bandwidthMeter: DefaultBandwidthMeter)

    /**
     * ExoPlayer for provider-supplied [androidx.media3.exoplayer.source.MediaSource] instances
     * (each source bundles its own [androidx.media3.datasource.DataSource]).
     */
    fun createForBundledSources(
        context: Context,
        preBufferMs: Int = AppPrefsStore.DEFAULT_PRE_BUFFER_MS,
    ): PlayerWithMeter {
        val minBufferMs = preBufferMs - 10_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                preBufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val renderersFactory = OpenTuneRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
        return PlayerWithMeter(player, bandwidthMeter)
    }
}
