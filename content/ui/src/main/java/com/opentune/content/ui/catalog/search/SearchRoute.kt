package com.opentune.content.ui.catalog.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.ui.Routes
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.CatalogNav
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.content.ui.catalog.player.PlayerStopEffect

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    endpointId: String,
    scopeLocationEncoded: String,
    sharedVm: NavSharedViewModel,
    viewModel: SearchViewModel,
    playerController: PlayerController,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }

    val client by viewModel.client.collectAsState()
    val initError by viewModel.initError.collectAsState()
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)
    val query by viewModel.query.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val restoreFocusRef = remember(endpointId, scopeDecoded) {
        viewModel.lastFocusedItemRef.value
    }

    LaunchedEffect(endpointId, scopeDecoded) {
        viewModel.initialize(endpointId, scopeDecoded)
    }

    PlayerStopEffect(playerController) {
        viewModel.refresh()
    }

    val imageLoader = viewModel.imageLoader

    when {
        initError != null -> Text("Error: $initError")
        client == null || imageLoader == null -> Text("Loading…")
        else -> SearchScreen(
            results = viewModel.results,
            query = query,
            searching = searching,
            imageLoader = imageLoader,
            titleLang = titleLang,
            initialFocusRef = restoreFocusRef,
            onBack = { nav.popBackStack() },
            onQueryChange = { viewModel.setQuery(it) },
            onItemFocused = { item -> viewModel.setLastFocusedItemRef(item.ref) },
            onOpenBrowse = { entry ->
                sharedVm.cache(entry)
                nav.navigate(Routes.browse(endpointId, entry))
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
