package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EntryInfo
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieOverviewScreen(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    viewModel: DetailViewModel,
    mediaCodecs: List<MediaCodecInfo> = emptyList(),
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
) {
    DetailOverviewShell(entryInfo = entryInfo) {
        Box(modifier = Modifier.fillMaxSize()) {
            DetailBackdrop(backdropUrl = entryInfo.backdrop.firstOrNull())

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailHeader(entryInfo = entryInfo, titleLang = titleLang)
                DetailBadges(entryInfo, mediaCodecs)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoviePlayButton(
                        resumeMs = resumeMs,
                        onResume = onResume,
                        onPlayFromStart = onPlayFromStart,
                    )
                    DetailButtons(
                        entryInfo = entryInfo,
                        viewModel = viewModel,
                    )
                }
                entryInfo.overview?.let { DetailOverviewSnippet(it) }
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
