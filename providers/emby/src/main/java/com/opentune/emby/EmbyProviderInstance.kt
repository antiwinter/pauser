package com.opentune.emby

import com.opentune.emby.dto.BaseItemDto
import com.opentune.emby.dto.DeviceProfile
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryList
import com.opentune.provider.EntryType
import com.opentune.provider.EntryUserData
import com.opentune.provider.ExternalUrl
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.PlaybackMimeTypes
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.StreamInfo
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CONTAINER_TYPES = setOf(
    "Folder", "BoxSet", "MusicAlbum",
    "MusicArtist", "Playlist", "CollectionFolder", "UserView",
)

private val NON_PLAYABLE_TYPES = CONTAINER_TYPES + setOf("Series", "Season")

class EmbyProviderInstance(
    private val fields: EmbyServerFieldsJson,
    private val deviceProfile: DeviceProfile,
    private val capabilities: PlatformCapabilities = PlatformCapabilities(emptyList(), emptyList()),
) : OpenTuneProviderInstance {

    private fun repo(): EmbyRepository = EmbyRepository(
        baseUrl = fields.baseUrl,
        userId = fields.userId,
        accessToken = fields.accessToken,
        deviceProfile = deviceProfile,
    )

    private fun BaseItemDto.toListItem(): EntryInfo? {
        val id = id ?: return null
        val type = type ?: ""
        val kind = when (type) {
            "Series" -> EntryType.Series
            "Season" -> EntryType.Season
            "Episode" -> EntryType.Episode
            in CONTAINER_TYPES -> EntryType.Folder
            else -> EntryType.Playable
        }
        val primaryTag = imageTags?.get("Primary")
        val cover = if (primaryTag != null) {
            EmbyImageUrls.imageUrl(
                baseUrl = fields.baseUrl,
                itemId = id,
                imageType = "Primary",
                tag = primaryTag,
                accessToken = fields.accessToken,
            )
        } else null
        return EntryInfo(
            id = id,
            title = name ?: id,
            type = kind,
            cover = cover,
            userData = userData?.let {
                EntryUserData(
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

    override suspend fun listEntry(location: String?, startIndex: Int, limit: Int): EntryList {
        val r = repo()
        return withContext(Dispatchers.IO) {
            if (location == null) {
                val views = r.getViews()
                EntryList(
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
                EntryList(
                    items = result.items.mapNotNull { it.toListItem() },
                    totalCount = result.totalRecordCount,
                )
            }
        }
    }

    override suspend fun search(scopeLocation: String, query: String): List<EntryInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val r = repo()
        return withContext(Dispatchers.IO) {
            val parentId: String? = scopeLocation.ifEmpty { null }
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

    override suspend fun getDetail(itemRef: String): EntryDetail {
        val r = repo()
        return withContext(Dispatchers.IO) {
            val item = r.getItem(itemRef, fields = EmbyFieldSets.DETAIL_FIELDS)
            val id = item.id ?: itemRef

            val logoTag = item.imageTags?.get("Logo")
            val logo = if (logoTag != null) {
                EmbyImageUrls.imageUrl(
                    baseUrl = fields.baseUrl,
                    itemId = id,
                    imageType = "Logo",
                    tag = logoTag,
                    accessToken = fields.accessToken,
                    maxHeight = 160,
                )
            } else null

            val backdrop = (item.backdropImageTags ?: emptyList()).mapIndexed { index, tag ->
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

            val streams = (item.mediaStreams ?: emptyList()).map { stream ->
                StreamInfo(
                    index = stream.index ?: 0,
                    type = stream.type ?: "",
                    codec = stream.codec,
                    title = stream.displayTitle,
                    language = stream.language,
                    isDefault = stream.isDefault ?: false,
                    isForced = stream.isForced ?: false,
                )
            }

            val isMedia = item.type !in NON_PLAYABLE_TYPES

            EntryDetail(
                title = item.name ?: itemRef,
                overview = item.overview,
                logo = logo,
                backdrop = backdrop,
                isMedia = isMedia,
                rating = item.communityRating,
                bitrate = bitrate,
                externalUrls = externalUrls,
                year = item.productionYear,
                providerIds = item.providerIds ?: emptyMap(),
                streams = streams,
                etag = item.etag,
            )
        }
    }

    override suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec {
        return withContext(Dispatchers.IO) {
            val r = repo()
            val startTicks = if (startMs > 0) startMs * 10_000L else null
            val info = r.getPlaybackInfo(itemRef, startTimeTicks = startTicks)
            val source = info.mediaSources.firstOrNull() ?: error("No media sources")
            val url = EmbyPlaybackUrlResolver.resolve(fields.baseUrl, source)
            val mimeType = PlaybackMimeTypes.fromContainers(source.transcodingContainer, source.container)
            val playMethod = EmbyPlaybackUrlResolver.playMethod(source)
            val item = r.getItem(itemRef)
            val title = item.name ?: itemRef
            val headers = mapOf("X-Emby-Token" to fields.accessToken)

            val subtitleTracks = source.mediaStreams
                .filter { it.type == "Subtitle" }
                .mapNotNull { stream ->
                    val index = stream.index ?: return@mapNotNull null
                    val label = stream.displayTitle ?: stream.language ?: "Subtitle $index"
                    val codec = stream.codec?.lowercase()
                    val isBitmapCodec = codec in setOf(
                        "pgssub", "hdmv_pgs_subtitle", "dvd_subtitle", "dvbsub",
                        "dvb_subtitle", "xsub", "microdvd",
                    )
                    val ext = when (codec) {
                        "ass", "ssa" -> "ass"
                        "vtt", "webvtt" -> "vtt"
                        else -> "srt"
                    }
                    val externalRef = when {
                        stream.isExternal == true ->
                            "${fields.baseUrl}/Videos/$itemRef/Subtitles/$index/Stream.$ext"
                        isBitmapCodec -> {
                            if ("ass" in capabilities.subtitleFormats)
                                "${fields.baseUrl}/Videos/$itemRef/Subtitles/$index/Stream.ass"
                            else
                                return@mapNotNull null
                        }
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
                url = url,
                headers = headers,
                mimeType = mimeType,
                customMediaSourceFactory = null,
                title = title,
                durationMs = null,
                hooks = hooks,
                subtitleTracks = subtitleTracks,
            )
        }
    }
}
