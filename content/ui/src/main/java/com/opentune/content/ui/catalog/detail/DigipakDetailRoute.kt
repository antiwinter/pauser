package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil3.ImageLoader
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
    val vmDigipakChildren by viewModel.digipakChildren.collectAsState()
    val vmSingleChild by viewModel.singleChild.collectAsState()
    val vmFocusedChildEntryId by viewModel.focusedChildEntryId.collectAsState()

    LaunchedEffect(entryInfo.id) {
        viewModel.loadDigipakChildren()
    }

    // Select initial child: prefer one with saved position, then first.
    // Also set the initial focus target when no explicit target is set.
    LaunchedEffect(vmDigipakChildren, vmSingleChild) {
        val child = vmSingleChild
            ?: vmDigipakChildren.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: vmDigipakChildren.firstOrNull()
            ?: return@LaunchedEffect
        Log.d(LOG_TAG, "initial child: id=${child.id}")
        playerController?.prepare(child)
        if (viewModel.focusedChildEntryId.value == null && vmDigipakChildren.isNotEmpty()) {
            viewModel.setFocusedChildEntryId(child.id)
        }
    }

    val resumePlay = { playerController?.play(); Unit }
    val playFromStart = {
        playerController?.playbackSession?.seekTo(0L)
        playerController?.play()
        Unit
    }
    val focusChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "focusChild: id=${child.id} title=${child.title}")
        sharedVm.cache(child)
        viewModel.setFocusedChildEntryId(child.id)
        playerController?.prepare(child)
        Unit
    }
    val selectChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "selectChild: id=${child.id} title=${child.title}")
        sharedVm.cache(child)
        viewModel.setFocusedChildEntryId(child.id)
        playerController?.prepare(child)
        playerController?.play()
        Unit
    }
    val playSingleChild: () -> Unit = run {
        val child = vmSingleChild
        {
            if (child != null) {
                Log.d(LOG_TAG, "playSingleChild: id=${child.id}")
                sharedVm.cache(child)
                playerController?.prepare(child)
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
        initialFocusId = vmFocusedChildEntryId,
        onFocusChild = focusChild,
        onResume = resumePlay,
        onPlayFromStart = playFromStart,
        onToggleFavorite = onToggleFavorite,
        onPlaySingleChild = playSingleChild,
        onSelectChild = selectChild,
    )
}
