package com.insomnia.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.insomnia.content.ui.catalog.player.PlayerController
import timber.log.Timber

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return
    val resumeMs = info.userData?.positionMs ?: 0L

    LaunchedEffect(info.ref) {
        Timber.d("initial: ref=${info.ref} resumeMs=$resumeMs")
        playerController?.prepare(info)
    }

    val resumePlay = { playerController?.play(); Unit }
    val playFromStart = {
        playerController?.playbackSession?.seekTo(0L)
        playerController?.play()
        Unit
    }

    DetailOverviewShell(viewModel = viewModel) {
        Box(modifier = Modifier.fillMaxSize()) {
            DetailBackdrop(backdropUrl = info.backdrop.firstOrNull())

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailHeader(viewModel = viewModel)
                DetailBadges(viewModel = viewModel, playerController = playerController)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoviePlayButton(
                        resumeMs = resumeMs,
                        onResume = resumePlay,
                        onPlayFromStart = playFromStart,
                    )
                    DetailButtons(viewModel = viewModel)
                }
                info.overview?.let { DetailOverviewSnippet(it) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoviePlayButton(
    resumeMs: Long,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    if (resumeMs > 0) {
        Button(
            onClick = onResume,
            modifier = Modifier.focusRequester(focusRequester),
        ) { Text("Resume") }
    } else {
        Button(
            onClick = onPlayFromStart,
            modifier = Modifier.focusRequester(focusRequester),
        ) { Text("Play") }
    }
}
