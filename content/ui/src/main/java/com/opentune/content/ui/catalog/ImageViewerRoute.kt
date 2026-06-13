package com.opentune.content.ui.catalog
import com.opentune.content.contract.EndpointClientRegistryHolder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerRoute(
    endpointId: String,
    itemId: String,
    onExit: () -> Unit,
) {
    var imageUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(itemId) {
        var spec: com.opentune.player.PlaybackSpec? = null
        val job = MainScope().launch {
            val instance = withContext(Dispatchers.IO) { EndpointClientRegistryHolder.get().getOrCreate(endpointId) }
            spec = withContext(Dispatchers.IO) { instance?.getPlaybackSpec(itemId, 0) }
            imageUrl = spec?.url
        }
        onDispose {
            job.cancel()
            spec?.hooks?.onDispose()
        }
    }
    imageUrl?.let { url ->
        com.opentune.imageviewer.ImageViewerScreen(
            url = url,
            title = itemId.substringAfterLast('/').substringAfterLast('\\'),
            onExit = onExit,
        )
    }
}
