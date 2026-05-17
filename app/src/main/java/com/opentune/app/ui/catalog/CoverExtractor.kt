package com.opentune.app.ui.catalog

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.opentune.app.OpenTuneApplication
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryType
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.MediaStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Returned by [rememberCoverExtractor].
 *
 * Null when the provider already supplies cover art
 * ([com.opentune.provider.OpenTuneProvider.providesCover] = true).
 */
data class CoverExtractor(
    /** Call with every newly loaded batch of items to schedule extraction. */
    val onItemsLoaded: ((List<EntryInfo>) -> Unit)?,
)

/**
 * Remembers cover extraction state for a single source.
 *
 * When [OpenTuneApplication.providerRegistry.provider(protocol).providesCover]
 * is true the returned [CoverExtractor] holds nulls — no work is done.
 *
 * When false, each batch supplied via [CoverExtractor.onItemsLoaded] is queued
 * for background extraction (bounded to 4 concurrent jobs). Each item calls
 * [OpenTuneProviderInstance.getPlaybackSpec] to resolve a media URL (same contract
 * as the player), passes [PlaybackSpec.url] to [MediaMetadataRetriever.setDataSource],
 * then calls [PlaybackSpec.hooks.onDispose] to release any resources the provider
 * allocated (e.g. SMB stream tokens). Results are persisted to
 * [com.opentune.storage.thumb.ThumbnailDiskCache] and
 * [com.opentune.storage.UserMediaStateStore], and exposed reactively via
 * [EntryInfo.cover] on the list.
 */
@Composable
fun rememberCoverExtractor(
    app: OpenTuneApplication,
    protocol: String,
    sourceId: String,
    instance: OpenTuneProviderInstance?,
    items: SnapshotStateList<EntryInfo>,
): CoverExtractor {
    val provider = remember(protocol) { app.providerRegistry.provider(protocol) }

    if (provider.providesCover || instance == null) {
        return remember { CoverExtractor(onItemsLoaded = null) }
    }

    val semaphore = remember { Semaphore(4) }
    val processedIds = remember { mutableSetOf<String>() }
    val pendingItems = remember { mutableStateOf<List<EntryInfo>>(emptyList()) }

    LaunchedEffect(pendingItems.value) {
        val batch = pendingItems.value
        if (batch.isEmpty()) return@LaunchedEffect
        batch.forEach { item ->
            if (item.type != EntryType.Playable && item.type != EntryType.Episode) return@forEach
            if (processedIds.contains(item.id)) return@forEach
            processedIds.add(item.id)
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    // 1. Check Room cache
                    val cached = app.storageBindings.mediaStateStore
                        .get(protocol, sourceId, item.id)?.coverCachePath
                    when {
                        cached == MediaStateEntity.COVER_FAILED -> return@withPermit
                        cached != null -> {
                            updateItemCover(items, item.id, cached)
                            return@withPermit
                        }
                    }

                    // 2. Check disk cache (in case DB row was lost)
                    val diskCached = app.storageBindings.thumbnailDiskCache.get(sourceId, item.id)
                    if (diskCached != null) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, item.id, diskCached,
                        )
                        updateItemCover(items, item.id, diskCached)
                        return@withPermit
                    }

                    // 3. Resolve the media URL via getPlaybackSpec — same path the player uses.
                    //    MMR reads the embedded picture from spec.url + spec.headers.
                    //    spec.hooks.onDispose() releases any provider resources (e.g. SMB tokens).
                    val spec = try {
                        instance.getPlaybackSpec(item.id, 0)
                    } catch (e: Exception) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, item.id, MediaStateEntity.COVER_FAILED,
                        )
                        return@withPermit
                    }

                    try {
                        val bytes = MediaMetadataRetriever().use { mmr ->
                            mmr.setDataSource(spec.url, spec.headers)
                            val embedded = mmr.embeddedPicture
                            if (embedded != null) {
                                embedded
                            } else {
                                val durationMs = mmr.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                                )?.toLongOrNull() ?: 0L
                                val bitmap = mmr.getFrameAtTime(
                                    (durationMs * 1000L) / 3L,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                )
                                if (bitmap != null) {
                                    ByteArrayOutputStream().use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                        bitmap.recycle()
                                        out.toByteArray()
                                    }
                                } else {
                                    null
                                }
                            }
                        }
                        if (bytes != null) {
                            val path = app.storageBindings.thumbnailDiskCache.put(
                                sourceId, item.id, bytes,
                            )
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, item.id, path,
                            )
                            updateItemCover(items, item.id, path)
                        } else {
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, item.id, MediaStateEntity.COVER_FAILED,
                            )
                        }
                    } catch (e: Exception) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, item.id, MediaStateEntity.COVER_FAILED,
                        )
                    } finally {
                        spec.hooks.onDispose()
                    }
                }
            }
        }
    }

    return remember { CoverExtractor(onItemsLoaded = { batch -> pendingItems.value = batch }) }
}

private fun updateItemCover(
    items: SnapshotStateList<EntryInfo>,
    itemId: String,
    path: String,
) {
    val idx = items.indexOfFirst { it.id == itemId }
    if (idx >= 0) items[idx] = items[idx].copy(cover = path)
}
