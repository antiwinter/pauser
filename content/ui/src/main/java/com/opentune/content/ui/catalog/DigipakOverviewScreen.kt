package com.opentune.content.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
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
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlaySingleChild: () -> Unit,
    onSelectChild: (EntryInfo) -> Unit,
) {
    val pagerState = rememberPagerState { 2 }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> DigipakPage1(
                    entryInfo = entryInfo,
                    titleLang = titleLang,
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    singleChild = singleChild,
                    children = children,
                    imageLoader = imageLoader,
                    mediaCodecs = mediaCodecs,
                    onPlaySingleChild = onPlaySingleChild,
                    onResume = onResume,
                    onPlayFromStart = onPlayFromStart,
                    onToggleFavorite = onToggleFavorite,
                    onSelectChild = onSelectChild,
                )
                1 -> DigipakPage2(entryInfo = entryInfo)
            }
        }

        PageIndicator(
            pageCount = 2,
            currentPage = pagerState.currentPage,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DigipakPage1(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    singleChild: EntryInfo?,
    children: List<EntryInfo>,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo> = emptyList(),
    onPlaySingleChild: () -> Unit,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectChild: (EntryInfo) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DetailBackdrop(backdropUrl = entryInfo.backdrop.firstOrNull())

        val childCount = entryInfo.childCount ?: 0
        val isSingleChild = childCount <= 1 && singleChild != null

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Logo or title
            val logoModel = artImageModel(entryInfo.logo)
            if (logoModel != null) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                val displayTitle = when (titleLang) {
                    TitleLang.Original -> entryInfo.originalTitle
                    else -> null
                } ?: entryInfo.title
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            // Single child: play buttons for direct play
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
                    Text(if (isFavorite) "♥ Liked" else "♡ Like")
                }
            }

            entryInfo.overview?.let { DetailOverviewSnippet(it) }

            // Multi-child: children row
            if (children.isNotEmpty()) {
                DigipakChildren(
                    children = children,
                    imageLoader = imageLoader,
                    onPlayChild = onSelectChild,
                )
            }
        }
    }
}

@Composable
private fun DigipakPage2(
    entryInfo: EntryInfo,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DetailBackdrop(backdropUrl = entryInfo.backdrop.getOrNull(1) ?: entryInfo.backdrop.firstOrNull())

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entryInfo.overview?.let { DetailOverviewFull(it) }
        }
    }
}
