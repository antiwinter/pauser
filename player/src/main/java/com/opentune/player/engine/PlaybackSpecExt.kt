package com.opentune.player.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.opentune.player.PlaybackSpec
import okhttp3.OkHttpClient

@UnstableApi
internal fun PlaybackSpec.toMediaSource(context: android.content.Context): MediaSource {
    fun headersInterceptor() = okhttp3.Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        chain.proceed(req)
    }
    val okHttp = httpClient
        .newBuilder()
        .apply { if (headers.isNotEmpty()) addInterceptor(headersInterceptor()) }
        .build()
    val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    val mediaItem = MediaItem.Builder()
        .setUri(Uri.parse(url))
        .apply { mimeType?.let { setMimeType(it) } }
        .build()
    return mediaSourceFactory.createMediaSource(mediaItem)
}
