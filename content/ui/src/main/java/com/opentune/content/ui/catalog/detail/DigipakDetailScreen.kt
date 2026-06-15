package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.LaunchedScrollToIndexIfNeeded
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.content.ui.catalog.player.PlayerController

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun DigipakDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val imageLoader = viewModel.imageLoader ?: return
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return

    val children by viewModel.subEntries.collectAsState()
    val subEntryIndex by viewModel.subEntryIndex.collectAsState()

    LaunchedEffect(info.ref, children) {
        if (children.isEmpty() || subEntryIndex != null) return@LaunchedEffect
        val idx = children.indexOfFirst { it.userData?.positionMs ?: 0L > 0L }
            .takeIf { it >= 0 } ?: 0
        viewModel.setSubEntry(idx)
    }

    LaunchedEffect(subEntryIndex) {
        val idx = subEntryIndex ?: return@LaunchedEffect
        val child = children[idx]
        playerController?.prepare(child)
    }

    val focusChild = { index: Int ->
        viewModel.setSubEntry(index)
    }

    val playChild: () -> Unit = {
        playerController?.play()
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

                if (children.isNotEmpty()) {
                    DigipakChildren(
                        children = children,
                        subEntryIndex = subEntryIndex,
                        imageLoader = imageLoader,
                        onFocusChild = focusChild,
                        onPlayChild = playChild,
                    )
                }
            }
        }
    }
}

@Composable
private fun DigipakChildren(
    children: List<EntryInfo>,
    imageLoader: ImageLoader,
    subEntryIndex: Int? = null,
    onFocusChild: (Int) -> Unit,
    onPlayChild: () -> Unit,
) {
    if (children.isEmpty()) return
    val listState = rememberLazyListState()

    LaunchedScrollToIndexIfNeeded(listState, subEntryIndex)

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(children, key = { _, child -> child.ref }) { index, child ->
            ThumbEntryComponent(
                item = child,
                onClick = onPlayChild,
                imageLoader = imageLoader,
                selected = index == subEntryIndex,
                modifier = Modifier.width(200.dp),
                onFocus = { onFocusChild(index) },
            )
        }
    }
}
