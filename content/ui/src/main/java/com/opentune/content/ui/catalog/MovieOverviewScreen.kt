package com.opentune.content.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import com.opentune.content.contract.EntryInfo
import com.opentune.storage.TitleLang
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieOverviewScreen(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> MoviePage1(
                    entryInfo = entryInfo,
                    titleLang = titleLang,
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    onResume = onResume,
                    onPlayFromStart = onPlayFromStart,
                    onToggleFavorite = onToggleFavorite,
                )
                1 -> MoviePage2(entryInfo = entryInfo)
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
private fun MoviePage1(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DetailBackdrop(backdropUrl = entryInfo.backdrop.firstOrNull())

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

            DetailBadges(entryInfo)
            DetailPlayButtons(
                resumeMs = resumeMs,
                isFavorite = isFavorite,
                hasContent = true,
                onResume = onResume,
                onPlayFromStart = onPlayFromStart,
                onToggleFavorite = onToggleFavorite,
            )
            entryInfo.overview?.let { DetailOverviewSnippet(it) }
        }
    }
}

@Composable
private fun MoviePage2(
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
