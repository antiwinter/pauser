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
import com.opentune.provider.ExternalUrl
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.MediaStreamInfo
import com.opentune.provider.MediaUserData
import com.opentune.provider.OpenTuneMediaSourceFactory
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private val CONTAINER_TYPES = setOf(
    "Folder", "BoxSet", "MusicAlbum",
    "MusicArtist", "Playlist", "CollectionFolder", "UserView",
)

private val NON_PLAYABLE_TYPES = CONTAINER_TYPES + setOf("Series", "Season")

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
        val kind = when (type) {
            "Series" -> MediaEntryKind.Series
            "Season" -> MediaEntryKind.Season
            "Episode" -> MediaEntryKind.Episode
            in CONTAINER_TYPES -> MediaEntryKind.Folder
            else -> MediaEntryKind.Playable
        }
        val primaryTag = imageTags?.get("Primary")
        val cover = if (primaryTag != null) {
            MediaArt.Http(
                EmbyImageUrls.imageUrl(
                    baseUrl = fields.baseUrl,
                    itemId = id,
                    imageType = "Primary",
                    tag = primaryTag,
                    accessToken = fields.accessToken,
                )
            )
        } else MediaArt.None
        return MediaListItem(
            id = id,
            title = name ?: id,
            kind = kind,
            cover = cover,
            userData = userData?.let {
                MediaUserData(
                    positionMs = (it.playbackPositionTicks ?: 0L) / 10_000L,
                    isFavorite = it.isFavorite ?: false,
                    played = it.played ?: false,
                )
            },
            originalTitle = originalTitle,
            genres = genres,
            communityRating = communityRating,
            studios = studios?.mapNotNull { it.name },
            etag = etag,
            indexNumber = indexNumber,
        )
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
                    fields = EmbyFieldSets.BROWSE_FIELDS,
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
            val result = r.getItems(
                parentId = parentId,
                recursive = true,
                searchTerm = q,
                startIndex = 0,
                limit = 100,
                fields = EmbyFieldSets.BROWSE_FIELDS,
            )
            result.items.mapNotNull { it.toListItem() }
        }
    }

    override suspend fun loadDetail(itemRef: String): MediaDetailModel {
        val r = repo()
        return withContext(Dispatchers.IO) {
            val item = r.getItem(itemRef, fields = EmbyFieldSets.DETAIL_FIELDS)
            val id = item.id ?: itemRef

            val logoTag = item.imageTags?.get("Logo")
            val logo = if (logoTag != null) {
                MediaArt.Http(
                    EmbyImageUrls.imageUrl(
                        baseUrl = fields.baseUrl,
                        itemId = id,
                        imageType = "Logo",
                        tag = logoTag,
                        accessToken = fields.accessToken,
                        maxHeight = 160,
                    )
                )
            } else MediaArt.None

            val backdropImages = (item.backdropImageTags ?: emptyList()).mapIndexed { index, tag ->
                EmbyImageUrls.imageUrl(
                    baseUrl = fields.baseUrl,
                    itemId = id,
                    imageType = "Backdrop",
                    tag = tag,
                    accessToken = fields.accessToken,
                    maxHeight = 1080,
                    index = index,
                )
            }

            val bitrate = item.mediaSources?.firstOrNull()?.bitrate

            val externalUrls = item.externalUrls?.mapNotNull {
                val name = it.name ?: return@mapNotNull null
                val url = it.url ?: return@mapNotNull null
                ExternalUrl(name, url)
            } ?: emptyList()

            val mediaStreams = (item.mediaStreams ?: emptyList()).map { stream ->
                MediaStreamInfo(
                    index = stream.index ?: 0,
                    type = stream.type ?: "",
                    codec = stream.codec,
                    displayTitle = stream.displayTitle,
                    language = stream.language,
                    isDefault = stream.isDefault ?: false,
                    isForced = stream.isForced ?: false,
                )
            }

            val canPlay = item.type !in NON_PLAYABLE_TYPES

            MediaDetailModel(
                title = item.name ?: itemRef,
                overview = item.overview,
                logo = logo,
                backdropImages = backdropImages,
                canPlay = canPlay,
                communityRating = item.communityRating,
                bitrate = bitrate,
                externalUrls = externalUrls,
                productionYear = item.productionYear,
                providerIds = item.providerIds ?: emptyMap(),
                mediaStreams = mediaStreams,
                etag = item.etag,
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

            val subtitleTracks = source.mediaStreams
                .filter { it.type == "Subtitle" }
                .mapNotNull { stream ->
                    val index = stream.index ?: return@mapNotNull null
                    val label = stream.displayTitle ?: stream.language ?: "Subtitle $index"
                    val codec = stream.codec?.lowercase()
                    val ext = when (codec) {
                        "ass", "ssa" -> "ass"
                        "vtt", "webvtt" -> "vtt"
                        else -> "srt"
                    }
                    val isBitmapCodec = codec in setOf(
                        "pgssub", "hdmv_pgs_subtitle", "dvd_subtitle", "dvbsub",
                        "dvb_subtitle", "xsub", "microdvd",
                    )
                    val externalRef = when {
                        stream.isExternal == true ->
                            "${fields.baseUrl}/Videos/$itemRef/Subtitles/$index/Stream.$ext?api_key=${fields.accessToken}"
                        isBitmapCodec ->
                            "${fields.baseUrl}/Videos/$itemRef/Subtitles/$index/Stream.ass?api_key=${fields.accessToken}"
                        else -> null
                    }
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
                displayTitle = title,
                durationMs = null,
                hooks = hooks,
                initialPositionMs = startMs,
                onPlaybackDispose = {},
                subtitleTracks = subtitleTracks,
            )
        }
    }
}
