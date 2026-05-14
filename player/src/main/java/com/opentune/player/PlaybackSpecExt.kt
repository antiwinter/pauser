package com.opentune.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.opentune.provider.PlaybackSpec
import okhttp3.OkHttpClient

@UnstableApi
internal fun PlaybackSpec.toMediaSource(context: Context): MediaSource {
    val factory = customMediaSourceFactory
    if (factory != null) {
        @Suppress("UNCHECKED_CAST")
        return (factory as () -> MediaSource)()
    }
    val streamUrl = checkNotNull(url) { "PlaybackSpec has neither url nor customMediaSourceFactory" }
    val okHttp = OkHttpClient.Builder()
        .apply {
            if (headers.isNotEmpty()) {
                addInterceptor { chain ->
                    val req = chain.request().newBuilder().apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    chain.proceed(req)
                }
            }
        }
        .build()
    val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    val mediaItem = MediaItem.Builder()
        .setUri(Uri.parse(streamUrl))
        .apply { mimeType?.let { setMimeType(it) } }
        .build()
    return mediaSourceFactory.createMediaSource(mediaItem)
}
