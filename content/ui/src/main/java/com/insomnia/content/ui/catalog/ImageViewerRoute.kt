package com.insomnia.content.ui.catalog

import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.player.EntryStateKeys
import com.insomnia.player.PlayingState

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
    itemRef: String,
    onExit: () -> Unit,
) {
    var imageUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(itemRef) {
        var specUrl: String? = null
        val job = MainScope().launch {
            val client = withContext(Dispatchers.IO) {
                EndpointClientRegistryHolder.get().getOrCreate(endpointId)
            }
            withContext(Dispatchers.IO) {
                val sources = client?.getPlaybackSources(itemRef)
                specUrl = sources?.firstOrNull()?.url
                imageUrl = specUrl
            }
        }
        onDispose {
            job.cancel()
            MainScope().launch {
                withContext(Dispatchers.IO) {
                    val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@withContext
                    client.updateEntryState(itemRef, EntryStateKeys.PLAYING_STATE, PlayingState.STOPPED.name)
                }
            }
        }
    }
    imageUrl?.let { url ->
        com.insomnia.imageviewer.ImageViewerScreen(
            url = url,
            title = itemRef.substringAfterLast('/').substringAfterLast('\\'),
            onExit = onExit,
        )
    }
}
