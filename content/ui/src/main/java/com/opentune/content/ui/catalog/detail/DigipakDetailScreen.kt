package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.components.ThumbEntryComponent
import com.opentune.content.ui.catalog.player.PlayerController

private const val LOG_TAG = "OT_DigipakDetail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DigipakDetailScreen(
    playerController: PlayerController?,
    viewModel: DetailViewModel,
) {
    val imageLoader = viewModel.imageLoader ?: return
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return

    val children by viewModel.digipakChildren.collectAsState()
    val singleChild by viewModel.singleChild.collectAsState()
    val initialFocusRef by viewModel.subEntryRef.collectAsState()

    val isSingleChild = (info.childCount ?: 0) <= 1 && singleChild != null
    val resumeMs = singleChild?.userData?.positionMs ?: 0L

    LaunchedEffect(info.ref) {
        viewModel.loadDigipakChildren()
    }

    LaunchedEffect(viewModel.entryStateKey) {
        playerController?.setContext(parentStateKey = viewModel.entryStateKey)
    }

    LaunchedEffect(children, singleChild) {
        val child = singleChild
            ?: children.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: children.firstOrNull()
            ?: return@LaunchedEffect
        Log.d(LOG_TAG, "initial child: ref=${child.ref}")
        playerController?.prepare(child)
        if (viewModel.subEntryRef.value == null && children.isNotEmpty()) {
            viewModel.setSubEntryRef(child.ref)
        }
    }

    val focusChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "focusChild: ref=${child.ref} title=${child.title}")
        viewModel.setSubEntryRef(child.ref)
        playerController?.prepare(child)
        Unit
    }
    val selectChild = { child: EntryInfo ->
        Log.d(LOG_TAG, "selectChild: ref=${child.ref} title=${child.title}")
        viewModel.setSubEntryRef(child.ref)
        playerController?.prepare(child)
        playerController?.play()
        Unit
    }
    val playSingleChild: () -> Unit = {
        val child = singleChild
        if (child != null) {
            Log.d(LOG_TAG, "playSingleChild: ref=${child.ref}")
            playerController?.prepare(child)
            playerController?.play()
        }
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

                if (isSingleChild) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (resumeMs > 0) {
                            Button(onClick = playSingleChild) { Text("Resume") }
                        }
                        Button(onClick = playSingleChild) {
                            Text(if (resumeMs > 0) "From start" else "Play")
                        }
                    }
                } else {
                    DetailBadges(viewModel = viewModel, playerController = playerController)
                }

                DetailButtons(viewModel = viewModel)

                info.overview?.let { DetailOverviewSnippet(it) }

                if (children.isNotEmpty()) {
                    DigipakChildren(
                        children = children,
                        imageLoader = imageLoader,
                        initialFocusRef = initialFocusRef,
                        onFocusChild = focusChild,
                        onPlayChild = selectChild,
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
    initialFocusRef: String? = null,
    onFocusChild: ((EntryInfo) -> Unit)? = null,
    onPlayChild: (EntryInfo) -> Unit,
) {
    if (children.isEmpty()) return
    val listState = rememberLazyListState()
    val refs = remember(children) { children.map { it.ref } }
    val focusRequesters = remember(refs) { List(refs.size) { FocusRequester() } }

    LaunchedEffect(initialFocusRef) {
        val targetIndex = initialFocusRef?.let { ref -> children.indexOfFirst { it.ref == ref } } ?: -1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            focusRequesters[targetIndex].requestFocus()
        }
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(children, key = { _, child -> child.ref }) { index, child ->
            ThumbEntryComponent(
                item = child,
                onClick = { onPlayChild(child) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp).focusRequester(focusRequesters[index]),
                onFocus = if (onFocusChild != null) { { onFocusChild(child) } } else null,
            )
        }
    }
}
