package com.insomnia.content.ui.catalog.browse

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.ui.Routes
import com.insomnia.storage.StorageBindingsHolder
import com.insomnia.storage.TitleLang
import com.insomnia.content.ui.catalog.NavSharedViewModel
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.content.ui.catalog.player.PlayerStopEffect

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
    val restoreFocusRef by viewModel.lastFocusedItemRef.collectAsState()

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
            onOpenLivePlayer = { item ->
                sharedVm.cache(item)
                nav.navigate(Routes.livePlayer(endpointId, item))
            },
            onOpenPlayer = { entry ->
                playerController.setClient(client!!)
                val startMs = if (entry.type == "LiveChannel") 0L else null
                playerController.prepare(entry, startMs)
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
