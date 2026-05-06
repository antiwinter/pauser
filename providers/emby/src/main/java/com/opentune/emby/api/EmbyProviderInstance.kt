package com.opentune.emby.api

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.provider.BrowsePageResult
import com.opentune.provider.CatalogRouteTokens
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneMediaSourceFactory
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private val CONTAINER_TYPES = setOf(
    "Folder", "Series", "Season", "BoxSet", "MusicAlbum",
    "MusicArtist", "Playlist", "CollectionFolder", "UserView",
)

@UnstableApi
class EmbyProviderInstance(
    private val fields: EmbyServerFieldsJson,
    private val deviceProfile: DeviceProfile,
) : OpenTuneProviderInstance {

    private fun repo(): EmbyRepository = EmbyRepository(
        baseUrl = fields.baseUrl,
        userId = fields.userId,
        accessToken = fields.accessToken,
        deviceProfile = deviceProfile,
    )

    private fun BaseItemDto.toListItem(): MediaListItem? {
        val id = id ?: return null
        val type = type ?: ""
        val kind = if (type in CONTAINER_TYPES) MediaEntryKind.Folder else MediaEntryKind.Playable
        val thumb = EmbyImageUrls.primaryThumb(baseUrl = fields.baseUrl, item = this, accessToken = fields.accessToken)
        val cover = if (thumb != null) MediaArt.Http(thumb) else MediaArt.None
        return MediaListItem(id = id, title = name ?: id, kind = kind, cover = cover)
    }

    override suspend fun loadBrowsePage(location: String, startIndex: Int, limit: Int): BrowsePageResult {
        val r = repo()
        return withContext(Dispatchers.IO) {
            if (location == CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT) {
                val views = r.getViews()
                BrowsePageResult(
                    items = views.items.mapNotNull { it.toListItem() },
                    totalCount = views.totalRecordCount,
                )
            } else {
                val result = r.getItems(
                    parentId = location,
                    recursive = false,
                    startIndex = startIndex,
                    limit = limit,
                )
                BrowsePageResult(
                    items = result.items.mapNotNull { it.toListItem() },
                    totalCount = result.totalRecordCount,
                )
            }
        }
    }

    override suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val r = repo()
        return withContext(Dispatchers.IO) {
            val parentId: String? = if (scopeLocation == CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT) null else scopeLocation
            val result = r.getItems(parentId = parentId, recursive = true, searchTerm = q, startIndex = 0, limit = 100)
            result.items.mapNotNull { it.toListItem() }
        }
    }

    override suspend fun loadDetail(itemRef: String): MediaDetailModel {
        val r = repo()
        return withContext(Dispatchers.IO) {
            val item = r.getItem(itemRef)
            val posterUrl = EmbyImageUrls.primaryPoster(baseUrl = fields.baseUrl, item = item, accessToken = fields.accessToken)
            val thumbUrl = EmbyImageUrls.primaryThumb(baseUrl = fields.baseUrl, item = item, accessToken = fields.accessToken)
            val poster = if (posterUrl != null) MediaArt.Http(posterUrl) else MediaArt.None
            val cover = if (thumbUrl != null) MediaArt.Http(thumbUrl) else MediaArt.None
            MediaDetailModel(
                itemKey = itemRef,
                title = item.name ?: itemRef,
                synopsis = item.overview,
                cover = cover,
                poster = poster,
                canPlay = true,
                resumePositionMs = 0L,
                favoriteSupported = true,
                isFavorite = false,
            )
        }
    }

    override suspend fun resolvePlayback(itemRef: String, startMs: Long, context: Context): PlaybackSpec {
        return withContext(Dispatchers.IO) {
            val r = repo()
            val startTicks = if (startMs > 0) startMs * 10_000L else null
            val info = r.getPlaybackInfo(itemRef, startTimeTicks = startTicks)
            val source = info.mediaSources.firstOrNull() ?: error("No media sources")
            val url = EmbyPlaybackUrlResolver.resolve(fields.baseUrl, source)
            val playMethod = EmbyPlaybackUrlResolver.playMethod(source)
            val item = r.getItem(itemRef)
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

            val mainFactory = OpenTuneMediaSourceFactory { progressiveFactory.createMediaSource(mediaItem) }
            val fallbackFactory = OpenTuneMediaSourceFactory { progressiveFactory.createMediaSource(mediaItem) }

            val subtitleTracks = source.mediaStreams
                .filter { it.type == "Subtitle" }
                .mapNotNull { stream ->
                    val index = stream.index ?: return@mapNotNull null
                    val label = stream.displayTitle ?: stream.language ?: "Subtitle $index"
                    val ext = when (stream.codec?.lowercase()) {
                        "ass", "ssa" -> "ass"
                        "vtt", "webvtt" -> "vtt"
                        else -> "srt"
                    }
                    val externalRef = if (stream.isExternal == true) {
                        "${fields.baseUrl}/Videos/$itemRef/Subtitles/$index/Stream.$ext?api_key=${fields.accessToken}"
                    } else null
                    SubtitleTrack(
                        trackId = index.toString(),
                        label = label,
                        language = stream.language,
                        isDefault = stream.isDefault ?: false,
                        isForced = stream.isForced ?: false,
                        externalRef = externalRef,
                    )
                }

            val hooks = EmbyPlaybackHooks(
                deviceProfile = deviceProfile,
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
                durationMs = null,
                audioFallbackOnly = true,
                hooks = hooks,
                audioDecodeUnsupportedBanner = null,
                initialPositionMs = startMs,
                onPlaybackDispose = {},
                subtitleTracks = subtitleTracks,
            )
        }
    }
}
