package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

private const val LOG_TAG = "OT_DigipakDetail"

@Composable
fun DigipakDetailRoute(
    protocol: String,
    endpointId: String,
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo>,
    playerController: PlayerController?,
    viewModel: DetailViewModel,
    sharedVm: NavSharedViewModel,
    onToggleFavorite: () -> Unit,
) {
    var playbackSelection by remember { mutableStateOf<PlaybackSelection?>(null) }

    val vmDigipakChildren by viewModel.digipakChildren.collectAsState()
    val vmSingleChild by viewModel.singleChild.collectAsState()

    LaunchedEffect(entryInfo.id) {
        viewModel.loadDigipakChildren()
    }

    // Select initial child: prefer one with saved position, then first.
    LaunchedEffect(vmDigipakChildren, vmSingleChild) {
        val child = vmSingleChild
            ?: vmDigipakChildren.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: vmDigipakChildren.firstOrNull()
            ?: return@LaunchedEffect
        playbackSelection = PlaybackSelection(child.id, child.userData?.positionMs ?: 0L)
    }

    // Set client once per endpoint.
    LaunchedEffect(endpointId) {
        val controller = playerController ?: return@LaunchedEffect
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        controller.setClient(client)
    }

    // Unified prepare.
    LaunchedEffect(playbackSelection) {
        val sel = playbackSelection ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        Log.d(LOG_TAG, "prepare: ref=${sel.itemRef} startMs=${sel.startMs}")
        controller.prepare(sel.itemRef, sel.startMs)
    }

    val playFromStart = {
        playbackSelection = playbackSelection?.copy(startMs = 0L)
        playerController?.play()
        Unit
    }
    val resumePlay = { playerController?.play(); Unit }
    val selectChild = { child: EntryInfo ->
        val startMs = child.userData?.positionMs ?: 0L
        sharedVm.cache(child)
        playbackSelection = PlaybackSelection(child.id, startMs)
        playerController?.play()
        Unit
    }
    val playSingleChild: () -> Unit = run {
        val child = vmSingleChild
        {
            if (child != null) {
                val startMs = child.userData?.positionMs ?: 0L
                sharedVm.cache(child)
                playbackSelection = PlaybackSelection(child.id, startMs)
                playerController?.play()
            }
        }
    }

    DigipakOverviewScreen(
        entryInfo = entryInfo,
        titleLang = titleLang,
        resumeMs = resumeMs,
        isFavorite = isFavorite,
        children = vmDigipakChildren,
        singleChild = vmSingleChild,
        imageLoader = imageLoader,
        mediaCodecs = mediaCodecs,
        onResume = resumePlay,
        onPlayFromStart = playFromStart,
        onToggleFavorite = onToggleFavorite,
        onPlaySingleChild = playSingleChild,
        onSelectChild = selectChild,
    )
}
