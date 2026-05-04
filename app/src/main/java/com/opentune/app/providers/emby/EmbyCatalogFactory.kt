package com.opentune.app.providers.emby

import com.opentune.app.OpenTuneApplication
import com.opentune.app.ui.catalog.BrowsePageResult
import com.opentune.app.ui.catalog.CatalogNav
import com.opentune.app.ui.catalog.CatalogResolveResult
import com.opentune.app.ui.catalog.MediaCatalogScreenBinding
import com.opentune.app.ui.catalog.MediaCatalogSource
import com.opentune.app.ui.catalog.MediaCover
import com.opentune.app.ui.catalog.MediaDetailModel
import com.opentune.app.ui.catalog.MediaEntryKind
import com.opentune.app.ui.catalog.MediaListItem
import com.opentune.emby.api.EmbyImageUrls
import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.storage.FavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EmbyCatalogFactory {
    fun browseSource(
        app: OpenTuneApplication,
        serverId: Long,
        locationDecoded: String,
    ): MediaCatalogSource = EmbyMediaCatalogSource(
        app = app,
        serverId = serverId,
        browseLocationDecoded = locationDecoded,
    )

    fun bindingForBrowse(
        app: OpenTuneApplication,
        serverId: Long,
        locationDecoded: String,
    ): CatalogResolveResult {
        val subtitle = when (locationDecoded) {
            CatalogNav.LIBRARIES_ROOT_SEGMENT -> "Libraries"
            else -> "Folder"
        }
        return CatalogResolveResult.Ok(
            MediaCatalogScreenBinding(
                catalog = browseSource(app, serverId, locationDecoded),
                logTag = "OpenTuneEmbyBrowse",
                onDispose = {},
                subtitle = subtitle,
            ),
        )
    }

    fun bindingForSearch(
        app: OpenTuneApplication,
        serverId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult = CatalogResolveResult.Ok(
        MediaCatalogScreenBinding(
            catalog = browseSource(app, serverId, scopeDecoded),
            logTag = "OpenTuneEmbySearch",
            onDispose = {},
            subtitle = "",
        ),
    )

    fun bindingForDetail(
        app: OpenTuneApplication,
        serverId: Long,
    ): CatalogResolveResult = CatalogResolveResult.Ok(
        MediaCatalogScreenBinding(
            catalog = browseSource(app, serverId, CatalogNav.LIBRARIES_ROOT_SEGMENT),
            logTag = "OpenTuneEmbyDetail",
            onDispose = {},
            subtitle = "",
        ),
    )
}

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
    private val app: OpenTuneApplication,
    private val serverId: Long,
    private val browseLocationDecoded: String,
) : MediaCatalogSource {

    private val isLibrariesRoot: Boolean
        get() = browseLocationDecoded == CatalogNav.LIBRARIES_ROOT_SEGMENT

    private suspend fun repo(): Pair<EmbyRepository, com.opentune.storage.EmbyServerEntity> {
        val server = withContext(Dispatchers.IO) { app.database.embyServerDao().getById(serverId) }
            ?: error("Server not found")
        val r = EmbyRepository(
            baseUrl = server.baseUrl,
            userId = server.userId,
            accessToken = server.accessToken,
            deviceProfile = app.deviceProfile,
        )
        return r to server
    }

    private fun BaseItemDto.toListItem(server: com.opentune.storage.EmbyServerEntity): MediaListItem? {
        val id = id ?: return null
        val type = type ?: ""
        val kind = if (type in CONTAINER_TYPES) MediaEntryKind.Folder else MediaEntryKind.Playable
        val thumb = EmbyImageUrls.primaryThumb(
            baseUrl = server.baseUrl,
            item = this,
            accessToken = server.accessToken,
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
        val (r, server) = repo()
        return withContext(Dispatchers.IO) {
            if (isLibrariesRoot) {
                val views = r.getViews()
                val items = views.items.mapNotNull { it.toListItem(server) }
                BrowsePageResult(items = items, totalCount = views.totalRecordCount)
            } else {
                val result = r.getItems(
                    parentId = browseLocationDecoded,
                    recursive = false,
                    startIndex = startIndex,
                    limit = limit,
                )
                val items = result.items.mapNotNull { it.toListItem(server) }
                BrowsePageResult(items = items, totalCount = result.totalRecordCount)
            }
        }
    }

    override suspend fun searchItems(query: String): List<MediaListItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val (r, server) = repo()
        return withContext(Dispatchers.IO) {
            val parentId: String? = if (isLibrariesRoot) null else browseLocationDecoded
            val result = r.getItems(
                parentId = parentId,
                recursive = true,
                searchTerm = q,
                startIndex = 0,
                limit = 100,
            )
            result.items.mapNotNull { it.toListItem(server) }
        }
    }

    override suspend fun loadDetail(itemKey: String): MediaDetailModel {
        val (r, server) = repo()
        return withContext(Dispatchers.IO) {
            val item = r.getItem(itemKey)
            val poster = EmbyImageUrls.primaryPoster(
                baseUrl = server.baseUrl,
                item = item,
                accessToken = server.accessToken,
            )
            val cover = if (poster != null) MediaCover.Http(poster) else MediaCover.None
            val key = "${serverId}_$itemKey"
            val prog = app.database.playbackProgressDao().get(key)
            val resume = prog?.positionMs ?: 0L
            val fav = app.database.favoriteDao().find(serverId, itemKey)
            MediaDetailModel(
                itemKey = itemKey,
                title = item.name ?: itemKey,
                synopsis = item.overview,
                cover = cover,
                canPlay = true,
                resumePositionMs = resume,
                favoriteSupported = true,
                isFavorite = fav != null,
            )
        }
    }

    override fun detailSupportsFavorite(): Boolean = true

    override suspend fun setFavorite(itemKey: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            val (r, _) = repo()
            val item = r.getItem(itemKey)
            val title = item.name ?: itemKey
            if (favorite) {
                app.database.favoriteDao().insert(
                    FavoriteEntity(
                        serverId = serverId,
                        itemId = itemKey,
                        title = title,
                        type = item.type,
                    ),
                )
            } else {
                app.database.favoriteDao().delete(serverId, itemKey)
            }
        }
    }
}
