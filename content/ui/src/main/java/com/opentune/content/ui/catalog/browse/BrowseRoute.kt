package com.opentune.content.ui.catalog.browse

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.Routes
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.content.ui.catalog.player.PlayerStopEffect

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    endpointId: String,
    initialEntryInfo: EntryInfo,
    viewModel: BrowseViewModel,
    sharedVm: NavSharedViewModel,
    playerController: PlayerController,
) {
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    val client by viewModel.client.collectAsState()
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val restoreFocusRef = remember(endpointId, initialEntryInfo.ref) {
        viewModel.lastFocusedItemRef.value
    }

    BackHandler { nav.popBackStack() }

    LaunchedEffect(endpointId) {
        viewModel.initialize(endpointId)
    }

    // Refresh data when returning to browse (e.g., from detail after playback).
    // refresh() guards against empty initial state, so harmless on first entry.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    PlayerStopEffect(playerController) {
        viewModel.refresh()
    }

    val imageLoader = viewModel.imageLoader

    when {
        client == null || imageLoader == null -> Text("Loading…")
        error != null && items.isEmpty() -> Text("Error: $error")
        else -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            totalCount = totalCount,
            loading = loading,
            error = error,
            subtitle = initialEntryInfo.title,
            titleLang = titleLang,
            imageLoader = imageLoader,
            initialFocusRef = restoreFocusRef,
            onLoadMore = { viewModel.loadMore() },
            onSearch = { nav.navigate(Routes.search(endpointId, initialEntryInfo.ref)) },
            onItemFocused = { item -> viewModel.setLastFocusedItemRef(item.ref) },
            onOpenBrowseLocation = { folderEntry ->
                sharedVm.cache(folderEntry)
                nav.navigate(Routes.browse(endpointId, folderEntry))
            },
            onOpenDetail = { item ->
                sharedVm.cache(item)
                nav.navigate(Routes.detail(endpointId, item))
            },
            onOpenPlayer = { entry ->
                playerController.setClient(client!!)
                playerController.prepare(entry)
                playerController.play()
            },
            onOpenImageViewer = { raw ->
                nav.navigate(Routes.imageViewer(endpointId, raw))
            },
            onOpenAudioUnsupported = { raw ->
                nav.navigate(Routes.AUDIO_UNSUPPORTED)
            },
        )
    }
}
