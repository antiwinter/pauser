package com.opentune.app.ui.catalog

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.MediaStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val LOG_TAG = "OT_SearchRoute"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    scopeLocationEncoded: String,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }
    var instance by remember { mutableStateOf<OpenTuneProviderInstance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val provider = remember(providerType) { app.providerRegistry.provider(providerType) }
    val coverOverrides = remember { androidx.compose.runtime.mutableStateMapOf<String, MediaArt>() }
    val extractSemaphore = remember { Semaphore(4) }
    val pendingItems = remember { mutableStateOf<List<MediaListItem>>(emptyList()) }

    LaunchedEffect(providerType, sourceId) {
        try {
            instance = app.instanceRegistry.getOrCreate(sourceId)
                ?: throw IllegalStateException("No instance for $sourceId")
        } catch (e: Exception) {
            error = e.message
        }
    }

    val inst = instance
    if (!provider.providesCover && inst != null) {
        LaunchedEffect(pendingItems.value) {
            val items = pendingItems.value
            if (items.isEmpty()) return@LaunchedEffect
            items.forEach { item ->
                if (coverOverrides.containsKey(item.id)) return@forEach
                launch(Dispatchers.IO) {
                    extractSemaphore.withPermit {
                        if (coverOverrides.containsKey(item.id)) return@withPermit
                        val cached = app.storageBindings.mediaStateStore
                            .get(providerType, sourceId, item.id)?.coverCachePath
                        when {
                            cached == MediaStateEntity.COVER_FAILED -> return@withPermit
                            cached != null -> {
                                coverOverrides[item.id] = MediaArt.LocalFile(cached)
                                return@withPermit
                            }
                        }
                        val diskCached = app.storageBindings.thumbnailDiskCache.get(sourceId, item.id)
                        if (diskCached != null) {
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                providerType, sourceId, item.id, diskCached,
                            )
                            coverOverrides[item.id] = MediaArt.LocalFile(diskCached)
                            return@withPermit
                        }
                        try {
                            val bytes = inst.withStream(item.id) { stream ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val dataSource = ItemStreamMediaDataSource(stream)
                                    MediaMetadataRetriever().use { r ->
                                        r.setDataSource(dataSource)
                                        r.embeddedPicture
                                    }
                                } else null
                            }
                            if (bytes != null) {
                                val path = app.storageBindings.thumbnailDiskCache.put(sourceId, item.id, bytes)
                                app.storageBindings.mediaStateStore.upsertCoverCache(
                                    providerType, sourceId, item.id, path,
                                )
                                coverOverrides[item.id] = MediaArt.LocalFile(path)
                            } else {
                                app.storageBindings.mediaStateStore.upsertCoverCache(
                                    providerType, sourceId, item.id, MediaStateEntity.COVER_FAILED,
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "Cover extraction failed for ${item.id}", e)
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                providerType, sourceId, item.id, MediaStateEntity.COVER_FAILED,
                            )
                        }
                    }
                }
            }
        }
    }

    when {
        error != null -> Text("Error: $error")
        inst == null -> Text("Loading\u2026")
        else -> {
            SearchScreen(
                logTag = "OT_Search_$sourceId",
                searchFn = { query -> inst.searchItems(scopeDecoded, query) },
                onBack = { nav.popBackStack() },
                onOpenBrowse = { raw -> nav.navigate(Routes.browse(providerType, sourceId, raw)) },
                onOpenDetail = { raw -> nav.navigate(Routes.detail(providerType, sourceId, raw)) },
                coverOverride = if (provider.providesCover) null else { id -> coverOverrides[id] },
                onItemsLoaded = if (provider.providesCover) null else { items -> pendingItems.value = items },
            )
        }
    }
}
