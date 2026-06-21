package com.opentune.player.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.opentune.player.PlaybackSpec
import okhttp3.OkHttpClient

/**
 * The single entry point for building a video [MediaSource]. Every prepare/rebuild path (initial
 * load, sidecar reselect, decoder fallback, recovery) routes through here so the bandwidth meter
 * and per-source headers can never be forgotten on a rebuilt client.
 *
 * Pass [sidecarSubtitle] to attach an external subtitle; the source is then built with
 * [DefaultMediaSourceFactory], which merges the text track into the video source.
 */
@UnstableApi
fun PlaybackSpec.toMediaSource(
    context: android.content.Context,
    sidecarSubtitle: MediaItem.SubtitleConfiguration? = null,
): MediaSource {
    val source = sources[state.sourceIndex]
    fun headersInterceptor() = okhttp3.Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            source.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        chain.proceed(req)
    }
    val okHttp = httpClient
        .newBuilder()
        .addInterceptor(BandwidthTracker.interceptor)
        .apply { if (source.headers.isNotEmpty()) addInterceptor(headersInterceptor()) }
        .build()
    val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
    val mediaItem = MediaItem.Builder()
        .setUri(Uri.parse(source.url))
        .apply { source.mimeType?.let { setMimeType(it) } }
        .apply { sidecarSubtitle?.let { setSubtitleConfigurations(listOf(it)) } }
        .build()
    val isHls = source.mimeType == "application/vnd.apple.mpegurl"
    // HLS uses its dedicated factory only when there's no sidecar; with a sidecar we need
    // DefaultMediaSourceFactory, which is the one that merges the external text track.
    if (isHls && sidecarSubtitle == null) {
        return HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    return mediaSourceFactory.createMediaSource(mediaItem)
}
