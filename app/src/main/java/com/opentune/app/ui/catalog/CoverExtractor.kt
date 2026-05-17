package com.opentune.app.ui.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.net.URL
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

/** Target cover thumbnail dimensions: smaller edge → [COVER_SHORT_EDGE], then center-crop to [COVER_W]×[COVER_H]. */
private const val COVER_W = 300
private const val COVER_H = 250
private const val COVER_SHORT_EDGE = 300

/**
 * Resize a bitmap so the smaller edge becomes [COVER_SHORT_EDGE], then center-crop to
 * [COVER_W]×[COVER_H].  If the source is already smaller than the target on either axis
 * it is still scaled up to maintain a uniform thumbnail size.
 */
private fun Bitmap.resizeAndCropToCover(): Bitmap {
    val srcW = width
    val srcH = height

    // Scale so smaller edge = COVER_SHORT_EDGE
    val scale = if (srcW <= srcH) {
        COVER_SHORT_EDGE.toFloat() / srcW
    } else {
        COVER_SHORT_EDGE.toFloat() / srcH
    }
    val scaledW = (srcW * scale).toInt()
    val scaledH = (srcH * scale).toInt()

    val scaled = Bitmap.createScaledBitmap(this, scaledW, scaledH, true)

    // Center-crop to COVER_W x COVER_H
    val cropW = COVER_W.coerceAtMost(scaledW)
    val cropH = COVER_H.coerceAtMost(scaledH)
    val cropX = (scaledW - cropW) / 2
    val cropY = (scaledH - cropH) / 2

    val cropped = if (cropW == scaledW && cropH == scaledH) {
        scaled
    } else {
        Bitmap.createBitmap(scaled, cropX, cropY, cropW, cropH)
    }

    if (cropped !== scaled) scaled.recycle()

    return cropped
}

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
            if (item.type != EntryType.Playable &&
                item.type != EntryType.Episode &&
                item.type != EntryType.Image) {
                return@forEach
            }
            if (processedIds.contains(item.id)) {
                return@forEach
            }
            processedIds.add(item.id)
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val id = item.id
                    // 1. Check Room cache
                    val cached = app.storageBindings.mediaStateStore
                        .get(protocol, sourceId, id)?.coverCachePath
                    when {
                        cached == MediaStateEntity.COVER_FAILED -> {
                            return@withPermit
                        }
                        cached != null -> {
                            updateItemCover(items, id, cached)
                            return@withPermit
                        }
                    }

                    // 2. Check disk cache (in case DB row was lost)
                    val diskCached = app.storageBindings.thumbnailDiskCache.get(sourceId, id)
                    if (diskCached != null) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, id, diskCached,
                        )
                        updateItemCover(items, id, diskCached)
                        return@withPermit
                    }

                    // 3. Resolve the media URL via getPlaybackSpec — same path the player uses.
                    //    MMR reads the embedded picture from spec.url + spec.headers.
                    //    spec.hooks.onDispose() releases any provider resources (e.g. SMB tokens).
                    val spec = try {
                        instance.getPlaybackSpec(id, 0)
                    } catch (e: Exception) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, id, MediaStateEntity.COVER_FAILED,
                        )
                        return@withPermit
                    }

                    // For images, download bytes, resize+crop, then dispose the token.
                    if (item.type == EntryType.Image) {
                        val bytes = try {
                            val raw = URL(spec.url).readBytes()
                            BitmapFactory.decodeByteArray(raw, 0, raw.size, null)
                                ?.resizeAndCropToCover()
                                ?.let { bmp ->
                                    ByteArrayOutputStream().use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                                        bmp.recycle()
                                        out.toByteArray()
                                    }
                                }
                        } catch (e: Exception) {
                            null
                        }
                        if (bytes != null) {
                            val path = app.storageBindings.thumbnailDiskCache.put(
                                sourceId, id, bytes,
                            )
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, id, path,
                            )
                            updateItemCover(items, id, path)
                        } else {
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, id, MediaStateEntity.COVER_FAILED,
                            )
                        }
                        spec.hooks.onDispose()
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
                                bitmap?.resizeAndCropToCover()?.let { bmp ->
                                    ByteArrayOutputStream().use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                        bmp.recycle()
                                        out.toByteArray()
                                    }
                                }
                            }
                        }
                        if (bytes != null) {
                            val path = app.storageBindings.thumbnailDiskCache.put(
                                sourceId, id, bytes,
                            )
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, id, path,
                            )
                            updateItemCover(items, id, path)
                        } else {
                            app.storageBindings.mediaStateStore.upsertCoverCache(
                                protocol, sourceId, id, MediaStateEntity.COVER_FAILED,
                            )
                        }
                    } catch (e: Exception) {
                        app.storageBindings.mediaStateStore.upsertCoverCache(
                            protocol, sourceId, id, MediaStateEntity.COVER_FAILED,
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
