package com.opentune.app.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.opentune.app.OpenTuneApplication
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryType
import com.opentune.server.SERVER_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * For providers that do not supply cover art, assigns a local-server generator URL
 * to each newly loaded item. Coil fetches and caches the image like any HTTP URL.
 */
@Composable
fun rememberAssetGenerator(
    app: OpenTuneApplication,
    protocol: String,
    sourceId: String,
    items: SnapshotStateList<EntryInfo>,
) {
    val providesCover = remember(protocol) { app.providerRegistry.provider(protocol).providesCover }
    val processedIds = remember { mutableSetOf<String>() }
    var trigger by remember { mutableStateOf(0) }

    if (providesCover) return

    LaunchedEffect(trigger, protocol, sourceId) {
        withContext(Dispatchers.Default) {
            items.forEachIndexed { index, item ->
                if (!processedIds.contains(item.id) &&
                    item.cover == null &&
                    item.type in setOf(EntryType.Playable, EntryType.Episode, EntryType.Image)
                ) {
                    processedIds.add(item.id)
                    val url = "http://localhost:$SERVER_PORT/genart/v1/$sourceId/${item.id}"
                    withContext(Dispatchers.Main) {
                        items[index] = item.copy(cover = url)
                    }
                }
            }
        }
    }

    LaunchedEffect(items.size) {
        trigger = items.size
    }
}
