package com.opentune.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

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

    /**
     * ExoPlayer using OkHttp for HTTP(S) streams (Emby direct/transcode URLs).
     * Default decoder stack uses Android [android.media.MediaCodec] only.
     */
    fun create(context: Context, okHttpClient: OkHttpClient): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(preferHardwareDecodersFirst)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
