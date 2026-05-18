package com.opentune.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.opentune.storage.AppConfigStore

@UnstableApi
object OpenTuneExoPlayer {

    data class PlayerWithMeter(val player: ExoPlayer, val bandwidthMeter: DefaultBandwidthMeter)

    /**
     * ExoPlayer for provider-supplied [androidx.media3.exoplayer.source.MediaSource] instances
     * (each source bundles its own [androidx.media3.datasource.DataSource]).
     *
     * Pass a [RetryableMediaCodecSelector.selector] as [codecSelector] when the decoder-fallback
     * path is enabled; otherwise the default Media3 selector is used. [bandwidthMeter] is owned
     * by the player after [ExoPlayer.Builder.setBandwidthMeter] and torn down automatically on
     * [ExoPlayer.release].
     */
    fun createForBundledSources(
        context: Context,
        preBufferMs: Int = AppConfigStore.DEFAULT_PRE_BUFFER_MS,
        codecSelector: MediaCodecSelector = MediaCodecSelector.DEFAULT,
    ): PlayerWithMeter {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                preBufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val renderersFactory = OpenTuneRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
        return PlayerWithMeter(player, bandwidthMeter)
    }
}
