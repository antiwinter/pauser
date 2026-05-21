package com.opentune.app.image

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

object ProxyImageLoader {
    private val loaders = mutableMapOf<String, ImageLoader>()

    fun get(endpointId: String, httpClient: OkHttpClient?, app: Application): ImageLoader {
        if (httpClient == null) return buildDefault(app)
        return loaders.getOrPut(endpointId) { buildProxied(httpClient, app) }
    }

    fun invalidate(endpointId: String) {
        loaders.remove(endpointId)?.shutdown()
    }

    fun clear() {
        loaders.values.forEach { it.shutdown() }
        loaders.clear()
    }

    private fun buildProxied(httpClient: OkHttpClient, app: Application): ImageLoader =
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "coil").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()

    private fun buildDefault(app: Application): ImageLoader =
        ImageLoader.Builder(app)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "coil").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
}
