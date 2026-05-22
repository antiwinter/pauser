package com.opentune.emby

import com.opentune.emby.dto.BaseItemDto
import com.opentune.emby.dto.DeviceProfile
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryList
import com.opentune.provider.EntryTag
import com.opentune.provider.EntryType
import com.opentune.provider.EntryUserData
import com.opentune.provider.ExternalUrl
import com.opentune.provider.SearchQuery
import com.opentune.provider.SortField
import com.opentune.provider.SortOrder
import com.opentune.provider.EndpointClient
import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.PlaybackMimeTypes
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.StreamInfo
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private fun SortField.toEmby(): String = when (this) {
    SortField.Title -> "SortName"
    SortField.DatePlayed -> "DatePlayed"
    SortField.DateAdded -> "DateCreated"
    SortField.CommunityRating -> "CommunityRating"
    SortField.Year -> "PremiereDate"
    SortField.IndexNumber -> "IndexNumber"
}

private fun SortOrder.toEmby(): String = when (this) {
    SortOrder.Ascending -> "Ascending"
    SortOrder.Descending -> "Descending"
}

private val CONTAINER_TYPES = setOf(
    "BoxSet", "MusicAlbum",
    "MusicArtist", "Playlist", "CollectionFolder", "UserView",
)

private val NON_PLAYABLE_TYPES = CONTAINER_TYPES + setOf("Series", "Season")

class EmbyProviderInstance(
    private val fields: EmbyServerFieldsJson,
    private val deviceProfile: DeviceProfile,
    private val capabilities: PlatformCapabilities = PlatformCapabilities(emptyList(), emptyList()),
    private val httpClient: OkHttpClient = OkHttpClient(),
) : EndpointClient {

    override var imageLoader: coil3.ImageLoader? = null

    private val repo: EmbyRepository = EmbyRepository(
        api = EmbyClientFactory.create(fields.baseUrl, fields.accessToken),
        userId = fields.userId,
        deviceProfile = deviceProfile,
    )

    private fun BaseItemDto.toListItem(): EntryInfo? {
        val id = id ?: return null
        val type = type ?: ""
        val kind = when (type) {
            "Series" -> EntryType.Series
            "Season" -> EntryType.Season
            "Episode" -> EntryType.Episode
            "Folder" -> EntryType.Digipak
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
            overview = overview,
            childCount = childCount,
            parentId = seasonId ?: parentBackdropItemId,
            seriesId = seriesId,
            seasonNumber = parentIndexNumber,
        )
    }

    override suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        val r = repo
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
                    sortBy = sortBy?.toEmby(),
                    sortOrder = sortOrder.toEmby(),
                )
                EntryList(
                    items = result.items.mapNotNull { it.toListItem() },
                    totalCount = result.totalRecordCount,
                )
            }
        }
    }

    override suspend fun search(scopeLocation: String, query: SearchQuery): EntryList {
        if (query.term.isEmpty() && query.years == null && query.genres == null &&
            query.countries == null && query.studios == null
        ) return EntryList(emptyList(), 0)
        val r = repo
        return withContext(Dispatchers.IO) {
            val parentId: String? = scopeLocation.ifEmpty { null }
            val excludeEmbyTypes = query.excludeTypes.mapNotNull { type ->
                when (type) {
                    EntryType.Folder -> "Folder"
                    EntryType.Series -> "Series"
                    EntryType.Season -> "Season"
                    EntryType.Episode -> "Episode"
                    else -> null
                }
            }.joinToString(",").ifEmpty { null }
            val result = r.getItems(
                parentId = parentId,
                excludeItemTypes = excludeEmbyTypes,
                recursive = true,
                searchTerm = query.term.ifEmpty { null },
                startIndex = query.startIndex,
                limit = query.limit,
                fields = EmbyFieldSets.BROWSE_FIELDS,
                years = query.years?.joinToString(","),
                genres = query.genres?.joinToString(","),
                studios = query.studios?.joinToString(","),
                countries = query.countries?.joinToString(","),
                sortBy = query.sortBy?.toEmby(),
                sortOrder = query.sortOrder.toEmby(),
            )
            EntryList(
                items = result.items.mapNotNull { it.toListItem() },
                totalCount = result.totalRecordCount,
            )
        }
    }

    override suspend fun getDetail(itemRef: String): EntryDetail {
        val r = repo
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
            val r = repo
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
                title = title,
                durationMs = null,
                hooks = hooks,
                subtitleTracks = subtitleTracks,
                httpClient = httpClient,
            )
        }
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        if (itemRefs.isEmpty()) return EntryList(emptyList(), 0)
        val r = repo
        return withContext(Dispatchers.IO) {
            val result = r.getItems(
                ids = itemRefs.joinToString(","),
                fields = EmbyFieldSets.BROWSE_FIELDS,
            )
            EntryList(
                items = result.items.mapNotNull { it.toListItem() },
                totalCount = result.totalRecordCount,
            )
        }
    }

    override suspend fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        val r = repo
        return withContext(Dispatchers.IO) {
            val filters = when (tag) {
                EntryTag.Recent -> "IsResumable"
                EntryTag.Favorite -> "IsFavorite"
                EntryTag.Played -> "IsPlayed"
                EntryTag.Unplayed -> "IsUnplayed"
            }
            val defaultSort = when (tag) {
                EntryTag.Recent, EntryTag.Played -> SortField.DatePlayed
                EntryTag.Favorite, EntryTag.Unplayed -> SortField.Title
            }
            val result = r.getItems(
                parentId = scopeLocation,
                recursive = true,
                startIndex = startIndex,
                limit = limit,
                fields = EmbyFieldSets.BROWSE_FIELDS,
                filters = filters,
                sortBy = (sortBy ?: defaultSort).toEmby(),
                sortOrder = sortOrder.toEmby(),
            )
            EntryList(
                items = result.items.mapNotNull { it.toListItem() },
                totalCount = result.totalRecordCount,
            )
        }
    }

    override suspend fun tagEntry(itemRef: String, tag: EntryTag, value: Boolean) {
        val r = repo
        withContext(Dispatchers.IO) {
            when (tag) {
                EntryTag.Favorite -> if (value) r.markFavorite(itemRef) else r.unmarkFavorite(itemRef)
                EntryTag.Played -> if (value) r.markPlayed(itemRef) else r.unmarkPlayed(itemRef)
                EntryTag.Recent, EntryTag.Unplayed -> Unit
            }
        }
    }
}
