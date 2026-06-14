package com.opentune.content.ui.catalog.detail

import android.util.Log
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.EntryStateKeys
import com.opentune.storage.decodeSeriesProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_SeriesDetail"

private suspend fun saveSeriesProgress(
    client: EndpointClient,
    seriesRef: String,
    seasonNumber: Int,
    episodeNumber: Int,
) {
    val packed = (seasonNumber.toLong() shl 32) or episodeNumber.toLong()
    client.updateEntryState(seriesRef, EntryStateKeys.SERIES_PROGRESS, packed.toString())
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val imageLoader = viewModel.imageLoader ?: return
    var pendingSeasonNumber by remember { mutableStateOf(0) }
    var pendingEpisodeNumber by remember { mutableStateOf(0) }
    var pendingAutoPlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client by viewModel.client.collectAsState()

    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return

    val seasons by viewModel.seasons.collectAsState()
    val seasonIndex by viewModel.seasonIndex.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val totalEpisodes by viewModel.totalEpisodes.collectAsState()
    val pageIndex by viewModel.pageIndex.collectAsState()
    val initialFocusRef by viewModel.subEntryRef.collectAsState()

    LaunchedEffect(info.ref) {
        val resumeMs = info.userData?.positionMs ?: 0L
        val (season, episode) = decodeSeriesProgress(resumeMs)
        if (season > 0) pendingSeasonNumber = season
        if (episode > 0) pendingEpisodeNumber = episode
        viewModel.loadSeasons()
    }

    LaunchedEffect(seasons, seasonIndex, pageIndex) {
        if (seasons.isNotEmpty() && pendingSeasonNumber > 0) {
            val season = seasons.firstOrNull { it.indexNumber == pendingSeasonNumber }
                ?: seasons.first()
            val targetPage = if (pendingEpisodeNumber > 0) (pendingEpisodeNumber - 1) / 50 else 0
            pendingSeasonNumber = 0
            viewModel.selectSeasonAndPageForProgress(season.ref, targetPage)
            return@LaunchedEffect
        }
        if (seasons.isNotEmpty() && viewModel.seasonIndex.value == null && pendingSeasonNumber == 0) {
            viewModel.setSeason(seasons.first().ref)
            return@LaunchedEffect
        }
        if (seasons.isNotEmpty()) viewModel.loadEpisodes()
    }

    LaunchedEffect(episodes) {
        if (episodes.isEmpty()) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        if (pendingAutoPlay) {
            pendingAutoPlay = false
            val episode = episodes.first()
            Log.d(LOG_TAG, "auto-advance season: ref=${episode.ref}")
            viewModel.setSubEntryRef(episode.ref)
            playerController?.prepare(episode, 0L)
            withContext(Dispatchers.IO) {
                saveSeriesProgress(c, info.ref, episode.seasonNumber ?: 0, episode.indexNumber ?: 0)
            }
            playerController?.play()
            return@LaunchedEffect
        }
        if (pendingEpisodeNumber > 0) {
            val episode = episodes.firstOrNull { it.indexNumber == pendingEpisodeNumber }
                ?: episodes.first()
            pendingEpisodeNumber = 0
            Log.d(LOG_TAG, "progress episode resolved: ref=${episode.ref}")
            viewModel.setSubEntryRef(episode.ref)
            return@LaunchedEffect
        }
        if (viewModel.subEntryRef.value == null) {
            viewModel.setSubEntryRef(episodes.first().ref)
        }
    }

    LaunchedEffect(playerController) {
        playerController?.setNextVideoCallback {
            val c = client ?: return@setNextVideoCallback
            val currentEpisodes = viewModel.episodes.value
            val currentRef = viewModel.subEntryRef.value
            val currentIdx = currentEpisodes.indexOfFirst { it.ref == currentRef }
            val nextEpisode = currentEpisodes.getOrNull(currentIdx + 1)
            if (nextEpisode != null) {
                Log.d(LOG_TAG, "requestNextVideo: ref=${nextEpisode.ref}")
                viewModel.setSubEntryRef(nextEpisode.ref)
                playerController.prepare(nextEpisode, 0L)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        saveSeriesProgress(
                            c, info.ref, nextEpisode.seasonNumber ?: 0, nextEpisode.indexNumber ?: 0,
                        )
                    }
                    playerController.play()
                }
            } else {
                val currentSeasons = viewModel.seasons.value
                val currentSeasonIdx = currentSeasons.indexOfFirst { it.ref == viewModel.seasonIndex.value }
                val nextSeason = currentSeasons.getOrNull(currentSeasonIdx + 1)
                if (nextSeason != null) {
                    Log.d(LOG_TAG, "requestNextVideo: advancing to season ${nextSeason.ref}")
                    pendingAutoPlay = true
                    viewModel.setSeason(nextSeason.ref)
                }
            }
        }
    }

    val focusEpisode = { episode: EntryInfo ->
        Log.d(LOG_TAG, "focusEpisode: ref=${episode.ref} title=${episode.title}")
        viewModel.setSubEntryRef(episode.ref)
        playerController?.prepare(episode)
        Unit
    }
    val selectEpisode = { episode: EntryInfo ->
        Log.d(LOG_TAG, "selectEpisode: ref=${episode.ref} title=${episode.title}")
        val c = client
        viewModel.setSubEntryRef(episode.ref)
        playerController?.prepare(episode)
        if (c != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    saveSeriesProgress(c, info.ref, episode.seasonNumber ?: 0, episode.indexNumber ?: 0)
                }
                playerController?.play()
            }
        } else {
            playerController?.play()
        }
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
                DetailButtons(viewModel = viewModel)
                info.overview?.let { DetailOverviewSnippet(it) }

                SeasonSelector(
                    seasons = seasons,
                    seasonIndex = seasonIndex,
                    onSelect = { viewModel.setSeason(it) },
                )
                EpisodeRow(
                    episodes = episodes,
                    imageLoader = imageLoader,
                    initialFocusRef = initialFocusRef,
                    onFocusEpisode = focusEpisode,
                    onPlayEpisode = selectEpisode,
                )
                EpisodePageSelector(
                    totalEpisodes = totalEpisodes,
                    currentPage = pageIndex,
                    onSelectPage = { viewModel.selectpageIndex(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonSelector(
    seasons: List<EntryInfo>,
    seasonIndex: String?,
    onSelect: (String) -> Unit,
) {
    if (seasons.size <= 1) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons) { season ->
            Button(onClick = { onSelect(season.ref) }) {
                Text(
                    text = season.title,
                    fontWeight = if (season.ref == seasonIndex)
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
    initialFocusRef: String? = null,
    onFocusEpisode: ((EntryInfo) -> Unit)? = null,
    onPlayEpisode: (EntryInfo) -> Unit,
) {
    if (episodes.isEmpty()) return
    val listState = rememberLazyListState()
    val refs = remember(episodes) { episodes.map { it.ref } }
    val focusRequesters = remember(refs) { List(refs.size) { FocusRequester() } }

    LaunchedEffect(initialFocusRef) {
        val targetIndex = initialFocusRef?.let { ref -> episodes.indexOfFirst { it.ref == ref } } ?: -1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            focusRequesters[targetIndex].requestFocus()
        }
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(episodes, key = { _, ep -> ep.ref }) { index, episode ->
            ThumbEntryComponent(
                item = episode,
                onClick = { onPlayEpisode(episode) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp).focusRequester(focusRequesters[index]),
                onFocus = if (onFocusEpisode != null) { { onFocusEpisode(episode) } } else null,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodePageSelector(
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
