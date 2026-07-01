package com.opentune.player.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.opentune.player.PlaybackSpec
import com.opentune.player.manager.subtitle.findSubtitleTrack
import com.opentune.player.manager.subtitle.toSidecarConfig
import okhttp3.OkHttpClient

/**
 * The single entry point for building a video [MediaSource]. Every prepare/rebuild path (initial
 * load, sidecar reselect, decoder fallback, recovery) routes through here so the bandwidth meter
 * and per-source headers can never be forgotten on a rebuilt client.
 *
 * The active external subtitle is derived from the spec's saved track id ([state] is the single
 * source of truth, kept current by [PlaybackSession]); when present it is attached via
 * [DefaultMediaSourceFactory], which merges the text track into the video source.
 */
@UnstableApi
fun PlaybackSpec.toMediaSource(
    context: android.content.Context,
): MediaSource {
    val source = sources[state.sourceIndex]
    val sidecarSubtitle = findSubtitleTrack(state.subtitleTrackId)?.toSidecarConfig()
    // Always send a User-Agent: some hosts reject the okhttp default UA that
    // OkHttpDataSource would otherwise send. Provider-supplied headers still win.
    val defaultUa = androidx.media3.common.util.Util.getUserAgent(context, context.packageName)
    fun headersInterceptor() = okhttp3.Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            source.headers.forEach { (k, v) -> header(k, v) }
            if (source.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", defaultUa)
            }
        }.build()
        chain.proceed(req)
    }
    val okHttp = httpClient
        .newBuilder()
        .addInterceptor(BandwidthTracker.interceptor)
        .addInterceptor(headersInterceptor())
        .build()
    val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
    // Wrap upstream in a disk cache so seeks back to already-read ranges don't re-fetch
    // (over LAN for SMB, over the network for HLS/HTTP). Cache keys = source URL, which is
    // stable for relay URLs. FLAG_IGNORE_CACHE_ON_ERROR: never fail a request due to the cache.
    val cachedFactory = CacheDataSource.Factory()
        .setCache(PlayerCache.get(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val mediaItem = MediaItem.Builder()
        .setUri(Uri.parse(source.url))
        .apply { source.mimeType?.let { setMimeType(it) } }
        .apply { sidecarSubtitle?.let { setSubtitleConfigurations(listOf(it)) } }
        .build()
    val isHls = source.mimeType == "application/vnd.apple.mpegurl"
    // HLS uses its dedicated factory only when there's no sidecar; with a sidecar we need
    // DefaultMediaSourceFactory, which is the one that merges the external text track.
    if (isHls && sidecarSubtitle == null) {
        return HlsMediaSource.Factory(cachedFactory)
            .createMediaSource(mediaItem)
    }
    val mediaSourceFactory = DefaultMediaSourceFactory(cachedFactory)
    return mediaSourceFactory.createMediaSource(mediaItem)
}
