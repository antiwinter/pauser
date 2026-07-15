@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.insomnia.content.ui.catalog.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.ui.catalog.LaunchedScrollToIndexIfNeeded
import com.insomnia.content.ui.catalog.components.ThumbEntryComponent
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.player.EntryStateKeys
import com.insomnia.player.ItemListInfo
import com.insomnia.storage.decodeSeriesProgress
import com.insomnia.storage.encodeSeriesProgress

private const val UI_EPISODE_PAGE_SIZE = 50

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

    LaunchedEffect(info, seasons) {
        if (seasons.isEmpty()) return@LaunchedEffect
        val (s, e) = decodeSeriesProgress(info.userData?.positionMs ?: 0L)
        vm.setEpisode(s, e)
    }
    fun playEpisode() {
        vm.updateEntryState(
            EntryStateKeys.POSITION_MS,
            encodeSeriesProgress(seasonIndex ?: 0, episodeIndex ?: 0).toString(),
        )
        playerController?.play()
    }

    LaunchedEffect(episodeIndex) {
        val idx = episodeIndex ?: return@LaunchedEffect
        val e = episodes[idx] ?: return@LaunchedEffect
        playerController?.prepare(e, e.userData?.positionMs)
        if (pendingAutoPlay) {
            playEpisode()
            pendingAutoPlay = false
        }
    }

    LaunchedEffect(playerController, episodeIndex, totalCount, episodes) {
        playerController?.setItemListCallback(
            cb = { index ->
                if (index in 0 until totalCount) {
                    pendingAutoPlay = true
                    vm.setEpisode(vm.subEntryIndex.value ?: 0, index)
                }
            },
            info = ItemListInfo(
                current = episodeIndex ?: 0,
                names = (0 until totalCount).map { episodes[it]?.title ?: "" },
            ),
        )
    }

    DetailOverviewShell(viewModel = vm) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailHeader(viewModel = vm)
            DetailBadges(viewModel = vm, playerController = playerController)
            DetailCredits(entryInfo = info)
            DetailButtons(viewModel = vm)
            info.overview?.let { DetailOverviewSnippet(it) }

            if (seasons.size > 1) {
                TextButtonsRow(
                    labels = seasons.map { it.title },
                    itemKeys = seasons.map { it.ref },
                    selectedIndex = seasonIndex,
                    onSelect = { i -> if (i != seasonIndex) vm.setEpisode(i, 0) },
                )
            }

            EpisodeRow(
                totalCount = totalCount,
                episodes = episodes,
                selectedIndex = episodeIndex,
                imageLoader = imageLoader,
                onFocusEpisode = { vm.setEpisode(seasonIndex ?: 0, it) },
                onPlayEpisode = { playEpisode() },
            )

            if (totalCount > UI_EPISODE_PAGE_SIZE) {
                TextButtonsRow(
                    labels = episodePageLabels(totalCount),
                    selectedIndex = (episodeIndex ?: 0) / UI_EPISODE_PAGE_SIZE,
                    onSelect = { page ->
                        vm.setEpisode(seasonIndex ?: 0, page * UI_EPISODE_PAGE_SIZE)
                    },
                )
            }
        }
    }
}

/** Bold = selected, backshade = focus. Select on OK only. */
@Composable
private fun TextButtonsRow(
    labels: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    itemKeys: List<Any>? = null,
) {
    if (labels.isEmpty()) return
    val listState = rememberLazyListState()
    val backshade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    LaunchedScrollToIndexIfNeeded(listState, selectedIndex)

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(labels, key = { i, _ -> itemKeys?.getOrNull(i) ?: i }) { i, label ->
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (focused) backshade else Color.Transparent),
            ) {
                Text(
                    text = label,
                    fontWeight = if (i == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
    selectedIndex: Int?,
    onFocusEpisode: (Int) -> Unit,
    onPlayEpisode: () -> Unit,
) {
    if (totalCount == 0) return
    val listState = rememberLazyListState()
    val resumeFocus = remember { FocusRequester() }
    val focusIndex = selectedIndex?.takeIf { episodes[it] != null }

    LaunchedScrollToIndexIfNeeded(listState, focusIndex, resumeFocus)

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(totalCount, key = { episodes[it]?.ref ?: it }) { index ->
            val episode = episodes[index]
            val mod = if (index == focusIndex) {
                Modifier.width(200.dp).focusRequester(resumeFocus)
            } else {
                Modifier.width(200.dp)
            }
            ThumbEntryComponent(
                item = episode,
                onClick = episode?.let { onPlayEpisode } ?: {},
                imageLoader = imageLoader,
                modifier = mod,
                onFocus = { onFocusEpisode(index) },
            )
        }
    }
}

private fun episodePageLabels(totalCount: Int): List<String> {
    val pageCount = (totalCount + UI_EPISODE_PAGE_SIZE - 1) / UI_EPISODE_PAGE_SIZE
    return List(pageCount) { page ->
        val start = page * UI_EPISODE_PAGE_SIZE + 1
        val end = minOf((page + 1) * UI_EPISODE_PAGE_SIZE, totalCount)
        "$start–$end"
    }
}
