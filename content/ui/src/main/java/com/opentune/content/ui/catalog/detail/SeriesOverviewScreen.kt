package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesOverviewScreen(
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    seasons: List<EntryInfo>,
    selectedSeasonId: String?,
    episodes: List<EntryInfo>,
    totalEpisodes: Int,
    episodePage: Int,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo> = emptyList(),
    initialEpisodeIndex: Int = 0,
    onFocusEpisode: (EntryInfo) -> Unit = {},
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
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
                DetailPlayButtons(
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    hasContent = true,
                    onResume = onResume,
                    onPlayFromStart = onPlayFromStart,
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
                    initialScrollIndex = initialEpisodeIndex,
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

