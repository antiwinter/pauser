package com.opentune.content.ui.catalog.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.TitleLang

private const val LOG_TAG = "OT_MovieDetail"

@Composable
fun MovieDetailRoute(
    protocol: String,
    endpointId: String,
    itemRefDecoded: String,
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    mediaCodecs: List<MediaCodecInfo>,
    playerController: PlayerController?,
    onToggleFavorite: () -> Unit,
) {
    var playbackSelection by remember { mutableStateOf<PlaybackSelection?>(null) }

    // Set initial playback selection when entry info is resolved.
    LaunchedEffect(entryInfo.id, entryInfo.type) {
        playbackSelection = PlaybackSelection(itemRefDecoded, resumeMs)
    }

    // Set client once per endpoint.
    LaunchedEffect(endpointId) {
        val controller = playerController ?: return@LaunchedEffect
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        controller.setClient(client)
    }

    // Unified prepare: fires whenever playbackSelection changes.
    LaunchedEffect(playbackSelection) {
        val sel = playbackSelection ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        Log.d(LOG_TAG, "prepare: ref=${sel.itemRef} startMs=${sel.startMs}")
        controller.prepare(sel.itemRef, sel.startMs)
    }

    val playFromStart = {
        playbackSelection = playbackSelection?.copy(startMs = 0L)
            ?: PlaybackSelection(itemRefDecoded, 0L)
        playerController?.play()
        Unit
    }
    val resumePlay = { playerController?.play(); Unit }

    MovieOverviewScreen(
        entryInfo = entryInfo,
        titleLang = titleLang,
        resumeMs = resumeMs,
        isFavorite = isFavorite,
        mediaCodecs = mediaCodecs,
        onResume = resumePlay,
        onPlayFromStart = playFromStart,
        onToggleFavorite = onToggleFavorite,
    )
}
