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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.EntryStateKeys
import com.opentune.storage.decodeSeriesProgress
import com.opentune.storage.encodeSeriesProgress

private const val UI_EPISODE_PAGE_SIZE = 50

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val vm = viewModel
    val imageLoader = vm.imageLoader ?: return
    var pendingAutoPlay by remember { mutableStateOf(false) }

    val entryInfo by vm.entryInfo.collectAsState()
    val info = entryInfo ?: return

    val seasons by vm.subEntries.collectAsState()
    val seasonIndex by vm.subEntryIndex.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val totalCount by vm.totalCount.collectAsState()
    val episodeIndex by vm.episodeIndex.collectAsState()

    LaunchedEffect(info.ref, seasons) {
        if (seasons.isEmpty()) return@LaunchedEffect
        val resumeMs = info.userData?.positionMs ?: 0L
        val (s, e) = decodeSeriesProgress(resumeMs)
        vm.setEpisode(s, e)
        // NOTE: sXeY are index in array, not info.indexNumber
        // indexNumber can be discontinuous if the provider has missing season/episode numbers.
        // so X Y here is absolute, can be translated to pageIndex and load that page into episodes.
        // only after page is ready, change episodeIndex state
    }

    fun saveSeriesProgress() {
        val s = seasonIndex ?: return
        val e = episodeIndex ?: return
        vm.updateEntryState(EntryStateKeys.POSITION_MS, encodeSeriesProgress(s, e).toString())
    }

    fun playEpisode() {
        saveSeriesProgress() // NOTE: no params, vm knows the current situation
        playerController?.play()
    }

    LaunchedEffect(episodeIndex) {
        val idx = episodeIndex ?: return@LaunchedEffect
        val e = episodes[idx] ?: return@LaunchedEffect
        // NOTE: episodeIndex is received, means episodes are ready
        playerController?.prepare(e, 0L)
        if (pendingAutoPlay) {
            playEpisode()
            pendingAutoPlay = false
        }
    }

    LaunchedEffect(playerController) {
        playerController?.setNextVideoCallback {
            pendingAutoPlay = true
            vm.nextEpisode()
        }
    }

    val focusEpisode = { y: Int ->
        vm.setEpisode(seasonIndex ?: 0, y)
        // NOTE: no need to prepare, will be done by episodeIndex change effect
    }

    DetailOverviewShell(viewModel = vm) {
        Box(modifier = Modifier.fillMaxSize()) {
            DetailBackdrop(backdropUrl = info.backdrop.firstOrNull())

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailHeader(viewModel = vm)
                DetailBadges(viewModel = vm, playerController = playerController)
                DetailButtons(viewModel = vm)
                info.overview?.let { DetailOverviewSnippet(it) }

                SeasonSelector(
                    seasons = seasons,
                    seasonIndex = seasonIndex,
                    onSelect = { vm.setEpisode(it, 0) },
                )
                // NOTE: EpisodeRow and PageSelector
                // EpisodeRow should be a infinite list, load additional entries on demand
                // PageSelector should be a reflector, reflecting the current position in row
                // it should change with episodeIndex change
                // When user select another page, it should issue a scroll to index command,
                // so the infinite list load and scroll to that position
                EpisodeRow(
                    totalCount = totalCount,
                    episodes = episodes,
                    selectedIndex = episodeIndex,
                    imageLoader = imageLoader,
                    onFocusEpisode = focusEpisode,
                    onPlayEpisode = { playEpisode() },
                )
                EpisodePageSelector(
                    totalCount = totalCount,
                    selectedIndex = episodeIndex,
                    onSelectPage = { page ->
                        vm.setEpisode(seasonIndex ?: 0, page * UI_EPISODE_PAGE_SIZE)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonSelector(
    seasons: List<EntryInfo>,
    seasonIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    if (seasons.size <= 1) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(seasons, key = { _, season -> season.ref }) { index, season ->
            Button(onClick = { onSelect(index) }) {
                Text(
                    text = season.title,
                    fontWeight = if (index == seasonIndex)
                        FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    totalCount: Int,
    episodes: Map<Int, EntryInfo>,
    imageLoader: ImageLoader,
    // NOTE: we don't use focus requester, but define a selected state index here.
    // the flow: onFocus -> setEpisode -> episodeIndex -> set style
    // the focus is just focus, don't apply special style
    selectedIndex: Int? = null,
    onFocusEpisode: (Int) -> Unit,
    onPlayEpisode: () -> Unit,
) {
    if (totalCount == 0) return
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        val idx = selectedIndex ?: return@LaunchedEffect
        if (idx in 0 until totalCount) {
            listState.scrollToItem(idx)
        }
    }

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(
            count = totalCount,
            key = { index -> episodes[index]?.ref ?: index },
        ) { index ->
            val episode = episodes[index]
            if (episode != null) {
                ThumbEntryComponent(
                    item = episode,
                    onClick = onPlayEpisode,
                    imageLoader = imageLoader,
                    selected = index == selectedIndex,
                    modifier = Modifier.width(200.dp),
                    onFocus = { onFocusEpisode(index) },
                )
            } else {
                Box(Modifier.width(200.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodePageSelector(
    totalCount: Int,
    selectedIndex: Int?,
    onSelectPage: (Int) -> Unit,
) {
    if (totalCount <= UI_EPISODE_PAGE_SIZE) return
    val currentPage = (selectedIndex ?: 0) / UI_EPISODE_PAGE_SIZE
    val pageCount = kotlin.math.ceil(totalCount / UI_EPISODE_PAGE_SIZE.toDouble()).toInt()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pageCount) { page ->
            val start = page * UI_EPISODE_PAGE_SIZE + 1
            val end = minOf((page + 1) * UI_EPISODE_PAGE_SIZE, totalCount)
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
