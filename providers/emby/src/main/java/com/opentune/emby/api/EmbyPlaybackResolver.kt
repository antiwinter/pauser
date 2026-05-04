package com.opentune.emby.api

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.provider.OpenTuneMediaSourceFactory
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.PlaybackResolveDeps
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.progressPersistenceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@UnstableApi
object EmbyPlaybackResolver {

    suspend fun resolve(
        deps: PlaybackResolveDeps,
        sourceId: Long,
        itemRef: String,
        startMs: Long,
        deviceProfile: DeviceProfile,
    ): PlaybackSpec = withContext(Dispatchers.IO) {
        val row = deps.serverStore.get(sourceId) ?: error("Server not found")
        require(row.providerId == OpenTuneProviderIds.HTTP_LIBRARY) { "Wrong provider for Emby resolver" }
        val fields = EmbyServerFieldsJson.parse(row.fieldsJson)
        val repo = EmbyRepository(
            baseUrl = fields.baseUrl,
            userId = fields.userId,
            accessToken = fields.accessToken,
            deviceProfile = deviceProfile,
        )
        val startTicks = if (startMs > 0) startMs * 10_000L else null
        val info = repo.getPlaybackInfo(itemRef, startTimeTicks = startTicks)
        val source = info.mediaSources.firstOrNull() ?: error("No media sources")
        val url = EmbyPlaybackUrlResolver.resolve(fields.baseUrl, source)
        val playMethod = EmbyPlaybackUrlResolver.playMethod(source)
        val item = repo.getItem(itemRef)
        val title = item.name ?: itemRef

        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("X-Emby-Token", fields.accessToken)
                    .build()
                chain.proceed(req)
            }
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
        val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        val mediaItem = MediaItem.fromUri(Uri.parse(url))

        val mainFactory = OpenTuneMediaSourceFactory {
            progressiveFactory.createMediaSource(mediaItem)
        }
        val fallbackFactory = OpenTuneMediaSourceFactory {
            progressiveFactory.createMediaSource(mediaItem)
        }

        val hooks = EmbyPlaybackHooks(
            progressStore = deps.progressStore,
            deviceProfile = deviceProfile,
            providerId = OpenTuneProviderIds.HTTP_LIBRARY,
            sourceId = sourceId,
            itemId = itemRef,
            playMethod = playMethod,
            playSessionId = info.playSessionId,
            mediaSourceId = source.id,
            liveStreamId = source.liveStreamId,
            baseUrl = fields.baseUrl,
            userId = fields.userId,
            accessToken = fields.accessToken,
        )

        PlaybackSpec(
            mediaSourceFactory = mainFactory,
            audioFallbackFactory = fallbackFactory,
            displayTitle = title,
            resumeKey = progressPersistenceKey(OpenTuneProviderIds.HTTP_LIBRARY, sourceId, itemRef),
            durationMs = null,
            audioFallbackOnly = true,
            hooks = hooks,
            audioDecodeUnsupportedBanner = null,
            initialPositionMs = startMs,
            onPlaybackDispose = {},
        )
    }
}
