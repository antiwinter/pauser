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
    val vmsubEntryRef by viewModel.subEntryRef.collectAsState()

    LaunchedEffect(entryInfo.ref) {
        viewModel.loadDigipakChildren()
    }

    // Select initial child: prefer one with saved position, then first.
    // Also set the initial focus target when no explicit target is set.
    LaunchedEffect(vmDigipakChildren, vmSingleChild) {
        val child = vmSingleChild
            ?: vmDigipakChildren.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: vmDigipakChildren.firstOrNull()
            ?: return@LaunchedEffect
        Log.d(LOG_TAG, "initial child: ref=${child.ref}")
        playerController?.prepare(child)
        if (viewModel.subEntryRef.value == null && vmDigipakChildren.isNotEmpty()) {
            viewModel.setSubEntryRef(child.ref)
        }
    }

    val focusChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "focusChild: ref=${child.ref} title=${child.title}")
        sharedVm.cache(child)
        viewModel.setSubEntryRef(child.ref)
        playerController?.prepare(child)
        Unit
    }
    val selectChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "selectChild: ref=${child.ref} title=${child.title}")
        sharedVm.cache(child)
        viewModel.setSubEntryRef(child.ref)
        playerController?.prepare(child)
        playerController?.play()
        Unit
    }
    val playSingleChild: () -> Unit = run {
        val child = vmSingleChild
        {
            if (child != null) {
                Log.d(LOG_TAG, "playSingleChild: ref=${child.ref}")
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
        initialFocusRef = vmsubEntryRef,
        onFocusChild = focusChild,
        onToggleFavorite = onToggleFavorite,
        onPlaySingleChild = playSingleChild,
        onSelectChild = selectChild,
    )
}
