package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DigipakOverviewScreen(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    children: List<EntryInfo>,
    singleChild: EntryInfo?,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo> = emptyList(),
    initialFocusId: String? = null,
    onFocusChild: (EntryInfo) -> Unit = {},
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlaySingleChild: () -> Unit,
    onSelectChild: (EntryInfo) -> Unit,
) {
    val isSingleChild = (entryInfo.childCount ?: 0) <= 1 && singleChild != null

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

                if (isSingleChild) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (resumeMs > 0) {
                            Button(onClick = onPlaySingleChild) { Text("Resume") }
                        }
                        Button(onClick = onPlaySingleChild) {
                            Text(if (resumeMs > 0) "From start" else "Play")
                        }
                    }
                } else {
                    DetailBadges(entryInfo, mediaCodecs)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onToggleFavorite) {
                        Text(if (isFavorite) "\u2665 Liked" else "\u2661 Like")
                    }
                }

                entryInfo.overview?.let { DetailOverviewSnippet(it) }

                if (children.isNotEmpty()) {
                    DigipakChildren(
                        children = children,
                        imageLoader = imageLoader,
                        initialFocusId = initialFocusId,
                        onFocusChild = onFocusChild,
                        onPlayChild = onSelectChild,
                    )
                }
            }
        }
    }
}

