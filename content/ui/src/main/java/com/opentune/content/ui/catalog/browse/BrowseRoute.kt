package com.opentune.content.ui.catalog.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    val vmItems by viewModel.items.collectAsState()
    val vmLoading by viewModel.loading.collectAsState()
    val vmError by viewModel.error.collectAsState()
    val vmTotal by viewModel.totalCount.collectAsState()
    val restoreFocusRef = remember(endpointId, initialEntryInfo.ref) {
        viewModel.lastFocusedItemRef.value
    }

    val items = remember { mutableStateListOf<EntryInfo>() }
    LaunchedEffect(vmItems) {
        if (vmItems.isEmpty()) return@LaunchedEffect
        if (items.size == vmItems.size && items.map { it.ref } == vmItems.map { it.ref }) {
            for (i in vmItems.indices) {
                items[i] = vmItems[i]
            }
        } else {
            items.clear()
            items.addAll(vmItems)
        }
    }

    LaunchedEffect(endpointId) {
        viewModel.initialize(endpointId)
    }

    PlayerStopEffect(playerController) {
        viewModel.refresh()
    }

    val imageLoader = viewModel.imageLoader

    when {
        client == null || imageLoader == null -> Text("Loading…")
        vmError != null && items.isEmpty() -> Text("Error: $vmError")
        else -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            loadMore = { startIndex, limit -> viewModel.listPage(startIndex, limit) },
            subtitle = initialEntryInfo.title,
            titleLang = titleLang,
            imageLoader = imageLoader,
            totalCount = vmTotal,
            loading = vmLoading,
            error = vmError,
            initialFocusRef = restoreFocusRef,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(endpointId, initialEntryInfo.ref)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
