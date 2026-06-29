package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.LaunchedScrollToIndexIfNeeded
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.EntryStateKeys
import com.opentune.storage.decodeSeriesProgress
import com.opentune.storage.encodeSeriesProgress
import kotlinx.coroutines.launch

private const val UI_EPISODE_PAGE_SIZE = 50

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DigipakDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val vm = viewModel
    val imageLoader = vm.imageLoader ?: return
    var pendingAutoPlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val entryInfo by vm.entryInfo.collectAsState()
    val info = entryInfo ?: return

    val children by vm.subEntries.collectAsState()
    val subEntryIndex by vm.subEntryIndex.collectAsState()

    LaunchedEffect(info.ref, children) {
        if (children.isEmpty() || subEntryIndex != null) return@LaunchedEffect
        // Resume the last-watched child. Single-level: season is always 0, the
        // episode index is packed into the vod item's POSITION_MS on play.
        val (_, ep) = decodeSeriesProgress(info.userData?.positionMs ?: 0L)
        vm.setSubEntry(ep.coerceIn(0, children.lastIndex))
    }

    val playChild: () -> Unit = {
        vm.updateEntryState(
            EntryStateKeys.POSITION_MS,
            encodeSeriesProgress(0, subEntryIndex ?: 0).toString(),
        )
        playerController?.play()
        Unit
    }

    LaunchedEffect(subEntryIndex) {
        val idx = subEntryIndex ?: return@LaunchedEffect
        val child = children.getOrNull(idx) ?: return@LaunchedEffect
        playerController?.prepare(child, child.userData?.positionMs)
        if (pendingAutoPlay) {
            playChild()
            pendingAutoPlay = false
        }
    }

    LaunchedEffect(playerController) {
        playerController?.setNextVideoCallback {
            pendingAutoPlay = true
            vm.nextSubEntry()
        }
    }

    val listState = rememberLazyListState()

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

                if (children.isNotEmpty()) {
                    DigipakChildren(
                        listState = listState,
                        children = children,
                        subEntryIndex = subEntryIndex,
                        imageLoader = imageLoader,
                        onFocusChild = { vm.setSubEntry(it) },
                        onPlayChild = { playChild() },
                    )
                }

                if (children.size > UI_EPISODE_PAGE_SIZE) {
                    TextButtonsRow(
                        labels = episodePageLabels(children.size),
                        selectedIndex = (subEntryIndex ?: 0) / UI_EPISODE_PAGE_SIZE,
                        onSelect = { page ->
                            scope.launch { listState.scrollToItem(page * UI_EPISODE_PAGE_SIZE) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DigipakChildren(
    listState: LazyListState,
    children: List<EntryInfo>,
    imageLoader: ImageLoader,
    subEntryIndex: Int? = null,
    onFocusChild: (Int) -> Unit,
    onPlayChild: () -> Unit,
) {
    if (children.isEmpty()) return
    val resumeFocus = remember { FocusRequester() }
    val focusIndex = subEntryIndex?.takeIf { children.getOrNull(it) != null }

    LaunchedScrollToIndexIfNeeded(listState, focusIndex, resumeFocus)

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(children, key = { _, child -> child.ref }) { index, child ->
            val mod = if (index == focusIndex) {
                Modifier.width(200.dp).focusRequester(resumeFocus)
            } else {
                Modifier.width(200.dp)
            }
            ThumbEntryComponent(
                item = child,
                onClick = onPlayChild,
                imageLoader = imageLoader,
                modifier = mod,
                onFocus = { onFocusChild(index) },
            )
        }
    }
}

/** Bold = selected, backshade = focus. Select on OK only. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TextButtonsRow(
    labels: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    val listState = rememberLazyListState()
    val backshade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    LaunchedScrollToIndexIfNeeded(listState, selectedIndex)

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(labels, key = { i, _ -> i }) { i, label ->
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

private fun episodePageLabels(totalCount: Int): List<String> {
    val pageCount = (totalCount + UI_EPISODE_PAGE_SIZE - 1) / UI_EPISODE_PAGE_SIZE
    return List(pageCount) { page ->
        val start = page * UI_EPISODE_PAGE_SIZE + 1
        val end = minOf((page + 1) * UI_EPISODE_PAGE_SIZE, totalCount)
        "$start–$end"
    }
}
