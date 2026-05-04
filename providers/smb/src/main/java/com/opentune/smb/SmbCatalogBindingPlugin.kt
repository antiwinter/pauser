package com.opentune.smb

import android.util.Log
import com.hierynomus.smbj.share.DiskShare
import com.opentune.provider.BrowsePageResult
import com.opentune.provider.CatalogBindingDeps
import com.opentune.provider.CatalogBindingPlugin
import com.opentune.provider.CatalogResolveResult
import com.opentune.provider.MediaCatalogScreenBinding
import com.opentune.provider.MediaCatalogSource
import com.opentune.provider.MediaCover
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.smb.R as SmbR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbCatalogBindingPlugin : CatalogBindingPlugin {

    override suspend fun browseBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        locationDecoded: String,
    ): CatalogResolveResult = try {
        val session = openSession(deps, sourceId)
        val share = session.share ?: return CatalogResolveResult.Err("No share")
        val displayPath = if (locationDecoded.isEmpty()) "/" else locationDecoded
        CatalogResolveResult.Ok(
            MediaCatalogScreenBinding(
                catalog = browseSource(share, locationDecoded),
                logTag = "OpenTuneSmbBrowse",
                onDispose = { session.close() },
                subtitle = "Path: $displayPath",
            ),
        )
    } catch (e: Exception) {
        Log.e("OpenTuneSmbBrowse", "SMB session", e)
        CatalogResolveResult.Err(e.message ?: "SMB error")
    }

    override suspend fun searchBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult = try {
        val session = openSession(deps, sourceId)
        val share = session.share ?: return CatalogResolveResult.Err("No share")
        CatalogResolveResult.Ok(
            MediaCatalogScreenBinding(
                catalog = browseSource(share, scopeDecoded),
                logTag = "OpenTuneSmbSearch",
                onDispose = { session.close() },
                subtitle = "",
            ),
        )
    } catch (e: Exception) {
        Log.e("OpenTuneSmbSearch", "SMB session", e)
        CatalogResolveResult.Err(e.message ?: "SMB error")
    }

    override suspend fun detailBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        itemRefDecoded: String,
    ): CatalogResolveResult = try {
        val session = openSession(deps, sourceId)
        val share = session.share ?: return CatalogResolveResult.Err("No share")
        val parent = itemRefDecoded.substringBeforeLast('/', missingDelimiterValue = "")
        CatalogResolveResult.Ok(
            MediaCatalogScreenBinding(
                catalog = browseSource(share, parent),
                logTag = "OpenTuneSmbDetail",
                onDispose = { session.close() },
                subtitle = "",
            ),
        )
    } catch (e: Exception) {
        Log.e("OpenTuneSmbDetail", "SMB session", e)
        CatalogResolveResult.Err(e.message ?: "SMB error")
    }
}

private suspend fun openSession(deps: CatalogBindingDeps, sourceId: Long): SmbSession {
    val row = withContext(Dispatchers.IO) { deps.serverStore.get(sourceId) }
        ?: error("SMB source not found")
    require(row.providerId == OpenTuneProviderIds.FILE_SHARE) { "Not a file-share server" }
    val f = SmbServerFieldsJson.parse(row.fieldsJson)
    return withContext(Dispatchers.IO) {
        SmbSession.open(
            SmbCredentials(
                host = f.host,
                shareName = f.shareName,
                username = f.username,
                password = f.password,
                domain = f.domain,
            ),
        )
    }
}

private fun browseSource(
    share: DiskShare,
    directoryPathDecoded: String,
): MediaCatalogSource = SmbMediaCatalogSource(
    share = share,
    directoryPath = directoryPathDecoded.replace('\\', '/').trimEnd('/'),
)

private class SmbMediaCatalogSource(
    private val share: DiskShare,
    private val directoryPath: String,
) : MediaCatalogSource {

    private fun pathForList(): String = directoryPath

    private fun coverFor(name: String, isDirectory: Boolean): MediaCover {
        val res = when {
            isDirectory -> SmbR.drawable.opentune_smb_folder
            isLikelyVideoFile(name) -> SmbR.drawable.opentune_smb_video
            else -> SmbR.drawable.opentune_smb_file
        }
        return MediaCover.DrawableRes(res)
    }

    private fun mapEntry(e: SmbListEntry): MediaListItem {
        val kind = when {
            e.isDirectory -> MediaEntryKind.Folder
            isLikelyVideoFile(e.name) -> MediaEntryKind.Playable
            else -> MediaEntryKind.Other
        }
        return MediaListItem(
            id = e.path,
            title = e.name + if (e.isDirectory) "/" else "",
            kind = kind,
            cover = coverFor(e.name, e.isDirectory),
        )
    }

    override suspend fun loadBrowsePage(startIndex: Int, limit: Int): BrowsePageResult {
        return withContext(Dispatchers.IO) {
            val listPath = pathForList()
            val all = share.listDirectory(listPath)
            val slice = all.drop(startIndex).take(limit)
            BrowsePageResult(
                items = slice.map { mapEntry(it) },
                totalCount = all.size,
            )
        }
    }

    override suspend fun searchItems(query: String): List<MediaListItem> {
        return withContext(Dispatchers.IO) {
            share.listDirectory(pathForList())
                .filterByName(query)
                .map { mapEntry(it) }
        }
    }

    override suspend fun loadDetail(itemKey: String): MediaDetailModel {
        val path = itemKey.replace('\\', '/')
        val name = path.substringAfterLast('/').ifEmpty { path }
        val video = isLikelyVideoFile(name)
        val coverRes = when {
            video -> SmbR.drawable.opentune_smb_video
            else -> SmbR.drawable.opentune_smb_file
        }
        return MediaDetailModel(
            itemKey = itemKey,
            title = name,
            synopsis = path,
            cover = MediaCover.DrawableRes(coverRes),
            canPlay = video,
            resumePositionMs = 0L,
            favoriteSupported = false,
            isFavorite = false,
        )
    }

    override fun detailSupportsFavorite(): Boolean = false

    override suspend fun setFavorite(itemKey: String, favorite: Boolean) {
        // no-op
    }
}
