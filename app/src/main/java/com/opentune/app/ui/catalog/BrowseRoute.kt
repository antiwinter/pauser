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
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaListItem
import com.opentune.storage.MediaStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val LOG_TAG = "OpenTuneBrowseRoute"

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Error(val message: String) : BrowseState
    data class Ready(val instance: com.opentune.provider.OpenTuneProviderInstance) : BrowseState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    locationEncoded: String,
) {
    val locationDecoded = remember(locationEncoded) { CatalogNav.decodeSegment(locationEncoded) }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    val provider = remember(providerType) { app.providerRegistry.provider(providerType) }
    val coverOverrides = remember { androidx.compose.runtime.mutableStateMapOf<String, MediaArt>() }
    val extractSemaphore = remember { Semaphore(4) }
    val pendingItems = remember { mutableStateOf<List<MediaListItem>>(emptyList()) }

    LaunchedEffect(app, providerType, sourceId) {
        state = BrowseState.Loading
        val instance = app.instanceRegistry.getOrCreate(sourceId)
        state = if (instance == null) {
            Log.e(LOG_TAG, "No instance for sourceId=$sourceId")
            BrowseState.Error("Server not found")
        } else {
            BrowseState.Ready(instance)
        }
    }

    val readyInstance = (state as? BrowseState.Ready)?.instance
    if (!provider.providesCover && readyInstance != null) {
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
                            val bytes = readyInstance.withStream(item.id) { stream ->
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

    when (val s = state) {
        is BrowseState.Loading -> Text("Loading\u2026")
        is BrowseState.Error -> Text("Error: ${s.message}")
        is BrowseState.Ready -> {
            val instance = s.instance
            BrowseScreen(
                logTag = "OpenTuneEmbyBrowse",
                loadPage = { startIndex, limit -> instance.loadBrowsePage(locationDecoded, startIndex, limit) },
                subtitle = if (locationDecoded == CatalogNav.LIBRARIES_ROOT_SEGMENT) "Libraries" else locationDecoded,
                onBack = { nav.popBackStack() },
                onSearch = {
                    nav.navigate(Routes.search(providerType, sourceId, locationDecoded))
                },
                onOpenBrowseLocation = { raw ->
                    nav.navigate(Routes.browse(providerType, sourceId, raw))
                },
                onOpenDetail = { raw ->
                    nav.navigate(Routes.detail(providerType, sourceId, raw))
                },
                coverOverride = if (provider.providesCover) null else { id -> coverOverrides[id] },
                onItemsLoaded = if (provider.providesCover) null else { items -> pendingItems.value = items },
            )
        }
    }
}
