package com.opentune.emby.api

import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.provider.BrowsePageResult
import com.opentune.provider.CatalogBindingDeps
import com.opentune.provider.CatalogBindingPlugin
import com.opentune.provider.CatalogResolveResult
import com.opentune.provider.CatalogRouteTokens
import com.opentune.provider.MediaCatalogScreenBinding
import com.opentune.provider.MediaCatalogSource
import com.opentune.provider.MediaCover
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbyCatalogBindingPlugin(
    private val deviceProfile: DeviceProfile,
) : CatalogBindingPlugin {

    override suspend fun browseBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        locationDecoded: String,
    ): CatalogResolveResult {
        val subtitle = when (locationDecoded) {
            CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT -> "Libraries"
            else -> "Folder"
        }
        return CatalogResolveResult.Ok(
            MediaCatalogScreenBinding(
                catalog = embyBrowseSource(deps, deviceProfile, sourceId, locationDecoded),
                logTag = "OpenTuneEmbyBrowse",
                onDispose = {},
                subtitle = subtitle,
            ),
        )
    }

    override suspend fun searchBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult = CatalogResolveResult.Ok(
        MediaCatalogScreenBinding(
            catalog = embyBrowseSource(deps, deviceProfile, sourceId, scopeDecoded),
            logTag = "OpenTuneEmbySearch",
            onDispose = {},
            subtitle = "",
        ),
    )

    override suspend fun detailBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        @Suppress("UNUSED_PARAMETER") itemRefDecoded: String,
    ): CatalogResolveResult = CatalogResolveResult.Ok(
        MediaCatalogScreenBinding(
            catalog = embyBrowseSource(deps, deviceProfile, sourceId, CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT),
            logTag = "OpenTuneEmbyDetail",
            onDispose = {},
            subtitle = "",
        ),
    )
}

private fun embyBrowseSource(
    deps: CatalogBindingDeps,
    deviceProfile: DeviceProfile,
    sourceId: Long,
    locationDecoded: String,
): MediaCatalogSource = EmbyMediaCatalogSource(
    deps = deps,
    deviceProfile = deviceProfile,
    serverId = sourceId,
    browseLocationDecoded = locationDecoded,
)

private val CONTAINER_TYPES = setOf(
    "Folder",
    "Series",
    "Season",
    "BoxSet",
    "MusicAlbum",
    "MusicArtist",
    "Playlist",
    "CollectionFolder",
    "UserView",
)

private class EmbyMediaCatalogSource(
    private val deps: CatalogBindingDeps,
    private val deviceProfile: DeviceProfile,
    private val serverId: Long,
    private val browseLocationDecoded: String,
) : MediaCatalogSource {

    private val isLibrariesRoot: Boolean
        get() = browseLocationDecoded == CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT

    private suspend fun repo(): Pair<EmbyRepository, EmbyServerFieldsJson> {
        val row = withContext(Dispatchers.IO) { deps.serverStore.get(serverId) }
            ?: error("Server not found")
        require(row.providerId == OpenTuneProviderIds.HTTP_LIBRARY) { "Not an HTTP library server" }
        val fields = EmbyServerFieldsJson.parse(row.fieldsJson)
        val r = EmbyRepository(
            baseUrl = fields.baseUrl,
            userId = fields.userId,
            accessToken = fields.accessToken,
            deviceProfile = deviceProfile,
        )
        return r to fields
    }

    private fun BaseItemDto.toListItem(fields: EmbyServerFieldsJson): MediaListItem? {
        val id = id ?: return null
        val type = type ?: ""
        val kind = if (type in CONTAINER_TYPES) MediaEntryKind.Folder else MediaEntryKind.Playable
        val thumb = EmbyImageUrls.primaryThumb(
            baseUrl = fields.baseUrl,
            item = this,
            accessToken = fields.accessToken,
        )
        val cover = if (thumb != null) MediaCover.Http(thumb) else MediaCover.None
        return MediaListItem(
            id = id,
            title = name ?: id,
            kind = kind,
            cover = cover,
        )
    }

    override suspend fun loadBrowsePage(startIndex: Int, limit: Int): BrowsePageResult {
        val (r, fields) = repo()
        return withContext(Dispatchers.IO) {
            if (isLibrariesRoot) {
                val views = r.getViews()
                val items = views.items.mapNotNull { it.toListItem(fields) }
                BrowsePageResult(items = items, totalCount = views.totalRecordCount)
            } else {
                val result = r.getItems(
                    parentId = browseLocationDecoded,
                    recursive = false,
                    startIndex = startIndex,
                    limit = limit,
                )
                val items = result.items.mapNotNull { it.toListItem(fields) }
                BrowsePageResult(items = items, totalCount = result.totalRecordCount)
            }
        }
    }

    override suspend fun searchItems(query: String): List<MediaListItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val (r, fields) = repo()
        return withContext(Dispatchers.IO) {
            val parentId: String? = if (isLibrariesRoot) null else browseLocationDecoded
            val result = r.getItems(
                parentId = parentId,
                recursive = true,
                searchTerm = q,
                startIndex = 0,
                limit = 100,
            )
            result.items.mapNotNull { it.toListItem(fields) }
        }
    }

    override suspend fun loadDetail(itemKey: String): MediaDetailModel {
        val (r, fields) = repo()
        return withContext(Dispatchers.IO) {
            val item = r.getItem(itemKey)
            val poster = EmbyImageUrls.primaryPoster(
                baseUrl = fields.baseUrl,
                item = item,
                accessToken = fields.accessToken,
            )
            val cover = if (poster != null) MediaCover.Http(poster) else MediaCover.None
            val resume = deps.progressStore.getPositionMs(OpenTuneProviderIds.HTTP_LIBRARY, serverId, itemKey) ?: 0L
            val fav = deps.favoritesStore.isFavorite(OpenTuneProviderIds.HTTP_LIBRARY, serverId, itemKey)
            MediaDetailModel(
                itemKey = itemKey,
                title = item.name ?: itemKey,
                synopsis = item.overview,
                cover = cover,
                canPlay = true,
                resumePositionMs = resume,
                favoriteSupported = true,
                isFavorite = fav,
            )
        }
    }

    override fun detailSupportsFavorite(): Boolean = true

    override suspend fun setFavorite(itemKey: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            val (r, _) = repo()
            val item = r.getItem(itemKey)
            val title = item.name ?: itemKey
            deps.favoritesStore.setFavorite(
                providerId = OpenTuneProviderIds.HTTP_LIBRARY,
                sourceId = serverId,
                itemId = itemKey,
                title = title,
                type = item.type,
                favorite = favorite,
            )
        }
    }
}
