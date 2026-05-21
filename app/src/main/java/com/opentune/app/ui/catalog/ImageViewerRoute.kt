package com.opentune.app.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.opentune.app.OpenTuneApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerRoute(
    app: OpenTuneApplication,
    endpointId: String,
    itemRefDecoded: String,
    onExit: () -> Unit,
) {
    var imageUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(itemRefDecoded) {
        var spec: com.opentune.provider.PlaybackSpec? = null
        val job = MainScope().launch {
            val instance = withContext(Dispatchers.IO) { app.endpointClientRegistry.getOrCreate(endpointId) }
            spec = withContext(Dispatchers.IO) { instance?.client?.getPlaybackSpec(itemRefDecoded, 0) }
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
            title = itemRefDecoded.substringAfterLast('/').substringAfterLast('\\'),
            onExit = onExit,
        )
    }
}
