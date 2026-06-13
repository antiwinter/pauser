package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

@Composable
fun MovieDetailRoute(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    mediaCodecs: List<MediaCodecInfo>,
    playerController: PlayerController?,
    onToggleFavorite: () -> Unit,
) {
    // Set initial selection for Movie (resumeMs from parent's entryState).
    LaunchedEffect(entryInfo.id) {
        Log.d("OT_MovieDetail", "initial: id=${entryInfo.id} resumeMs=$resumeMs")
        playerController?.prepare(entryInfo)
    }

    val resumePlay = { playerController?.play(); Unit }
    val playFromStart = {
        playerController?.playbackSession?.seekTo(0L)
        playerController?.play()
        Unit
    }

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