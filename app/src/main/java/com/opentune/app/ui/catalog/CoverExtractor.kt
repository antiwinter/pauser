package com.opentune.app.ui.catalog

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.opentune.app.OpenTuneApplication
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.MediaStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val LOG_TAG = "OT_CoverExtractor"

/**
 * Returned by [rememberCoverExtractor].
 *
 * Null when the provider already supplies cover art
 * ([com.opentune.provider.OpenTuneProvider.providesCover] = true).
 */
data class CoverExtractor(
    /** Call with every newly loaded batch of items to schedule extraction. */
    val onItemsLoaded: ((List<MediaListItem>) -> Unit)?,
)

/**
 * Remembers cover extraction state for a single source.
 *
 * When [OpenTuneApplication.providerRegistry.provider(providerType).providesCover]
 * is true the returned [CoverExtractor] holds nulls — no work is done.
 *
 * When false, each batch supplied via [CoverExtractor.onItemsLoaded] is queued
 * for background stream extraction (bounded to 4 concurrent jobs). Results are
 * persisted to [com.opentune.storage.thumb.ThumbnailDiskCache] and
 * [com.opentune.storage.UserMediaStateStore], and exposed reactively via
 * [CoverExtractor.coverOverride].
 */
@Composable
fun rememberCoverExtractor(
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    instance: OpenTuneProviderInstance?,
    items: SnapshotStateList<MediaListItem>,
): CoverExtractor {
    val provider = remember(providerType) { app.providerRegistry.provider(providerType) }

    if (provider.providesCover || instance == null) {
        return remember { CoverExtractor(onItemsLoaded = null) }
    }

    val semaphore = remember { Semaphore(4) }
    val processedIds = remember { mutableSetOf<String>() }
    val pendingItems = remember { mutableStateOf<List<MediaListItem>>(emptyList()) }

    LaunchedEffect(pendingItems.value) {
        val batch = pendingItems.value
        if (batch.isEmpty()) return@LaunchedEffect
        batch.forEach { item ->
            if (processedIds.contains(item.id)) return@forEach
            processedIds.add(item.id)
            launch(Dispatchers.IO) {
                semaphore.withPermit {

                    // 1. Check Room cache
                    val cached = app.storageBindings.mediaStateStore
                        .get(providerType, sourceId, item.id)?.coverCachePath
                    when {
                        cached == MediaStateEntity.COVER_FAILED -> return@withPermit
                        cached != null -> {
                            updateItemCover(items, item.id, MediaArt.LocalFile(cached))
                            return@withPermit
                        }
                    }

                    // 2. Check disk cache (in case DB row was lost)
                    val diskCached = app.storageBindings.thumbnailDiskCache.get(sourceId, item.id)
                    if (diskCached != null) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            providerType, sourceId, item.id, diskCached,
                        )
                        updateItemCover(items, item.id, MediaArt.LocalFile(diskCached))
                        return@withPermit
                    }

                    // 3. Extract from stream
                    try {
                        val bytes = instance.withStream(item.id) { stream ->
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
                            updateItemCover(items, item.id, MediaArt.LocalFile(path))
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

    return remember { CoverExtractor(onItemsLoaded = { batch -> pendingItems.value = batch }) }
}

private fun updateItemCover(
    items: SnapshotStateList<MediaListItem>,
    itemId: String,
    art: MediaArt,
) {
    val idx = items.indexOfFirst { it.id == itemId }
    if (idx >= 0) items[idx] = items[idx].copy(cover = art)
}
