package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesOverviewScreen(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    isFavorite: Boolean,
    seasons: List<EntryInfo>,
    selectedSeasonId: String?,
    episodes: List<EntryInfo>,
    totalEpisodes: Int,
    episodePage: Int,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo> = emptyList(),
    initialFocusId: String? = null,
    onFocusEpisode: (EntryInfo) -> Unit = {},
    onToggleFavorite: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onSelectEpisode: (EntryInfo) -> Unit,
    onSelectPage: (Int) -> Unit,
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
                DetailButtons(
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                )
                entryInfo.overview?.let { DetailOverviewSnippet(it) }

                SeasonSelector(
                    seasons = seasons,
                    selectedSeasonId = selectedSeasonId,
                    onSelect = onSelectSeason,
                )
                EpisodeRow(
                    episodes = episodes,
                    imageLoader = imageLoader,
                    initialFocusId = initialFocusId,
                    onFocusEpisode = onFocusEpisode,
                    onPlayEpisode = onSelectEpisode,
                )
                EpisodePager(
                    totalEpisodes = totalEpisodes,
                    currentPage = episodePage,
                    onSelectPage = onSelectPage,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonSelector(
    seasons: List<EntryInfo>,
    selectedSeasonId: String?,
    onSelect: (String) -> Unit,
) {
    if (seasons.size <= 1) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons) { season ->
            Button(onClick = { onSelect(season.id) }) {
                Text(
                    text = season.title,
                    fontWeight = if (season.id == selectedSeasonId)
                        FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episodes: List<EntryInfo>,
    imageLoader: ImageLoader,
    initialFocusId: String? = null,
    onFocusEpisode: ((EntryInfo) -> Unit)? = null,
    onPlayEpisode: (EntryInfo) -> Unit,
) {
    if (episodes.isEmpty()) return
    val listState = rememberLazyListState()
    val targetIndex = if (initialFocusId != null) episodes.indexOfFirst { it.id == initialFocusId } else -1
    val focusRequesters = remember(episodes) { List(episodes.size) { FocusRequester() } }

    LaunchedEffect(episodes, initialFocusId) {
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
        }
    }
    LaunchedEffect(episodes, initialFocusId, targetIndex) {
        if (targetIndex >= 0) {
            focusRequesters[targetIndex].requestFocus()
        }
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(episodes, key = { _, ep -> ep.id }) { index, episode ->
            ThumbEntryComponent(
                item = episode,
                onClick = { onPlayEpisode(episode) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp).focusRequester(focusRequesters[index]),
                onFocus = if (onFocusEpisode != null) {{ onFocusEpisode(episode) }} else null,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodePager(
    totalEpisodes: Int,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    if (totalEpisodes <= 50) return
    val pageCount = kotlin.math.ceil(totalEpisodes / 50.0).toInt()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pageCount) { page ->
            val start = page * 50 + 1
            val end = minOf((page + 1) * 50, totalEpisodes)
            Button(onClick = { onSelectPage(page) }) {
                Text(
                    text = "$start–$end",
                    fontWeight = if (page == currentPage)
                        FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
