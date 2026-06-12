package com.opentune.content.ui.catalog.search
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.storage.StorageBindingsHolder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.ui.Routes
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.SearchQuery
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.ArtUrlInjector
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.CatalogNav

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    protocol: String,
    endpointId: String,
    scopeLocationEncoded: String,
    sharedVm: NavSharedViewModel,
    viewModel: SearchViewModel,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }
    var client by remember { mutableStateOf<EndpointClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)
    val query by viewModel.query.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val lastFocusedItemId by viewModel.lastFocusedItemId.collectAsState()

    LaunchedEffect(protocol, endpointId) {
        try {
            client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                ?: throw IllegalStateException("No instance for $endpointId")
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(client, scopeDecoded, protocol, endpointId) {
        val c = client ?: return@LaunchedEffect
        viewModel.initialize { q ->
            c.search(scopeDecoded, SearchQuery(term = q)).items
                .let { ArtUrlInjector.apply(it, protocol, endpointId) }
        }
    }

    when {
        error != null -> Text("Error: $error")
        client == null -> Text("Loading…")
        else -> {
            val c = client!!
            SearchScreen(
                results = viewModel.results,
                query = query,
                searching = searching,
                imageLoader = c.imageLoader!!,
                titleLang = titleLang,
                initialFocusId = lastFocusedItemId,
                onBack = { nav.popBackStack() },
                onQueryChange = { viewModel.setQuery(it) },
                onItemFocused = { item -> viewModel.setLastFocusedItemId(item.id) },
                onOpenBrowse = { entry ->
                    sharedVm.cache(entry)
                    nav.navigate(Routes.browse(protocol, endpointId, entry))
                },
                onOpenDetail = { item ->
                    sharedVm.cache(item)
                    nav.navigate(Routes.detail(protocol, endpointId, item.id, item))
                },
                onOpenPlayer = { entry ->
                    sharedVm.cache(entry)
                    nav.navigate(Routes.player(protocol, endpointId, entry.id, entry))
                },
                onOpenImageViewer = { raw ->
                    nav.navigate(Routes.imageViewer(protocol, endpointId, raw))
                },
                onOpenAudioUnsupported = { raw ->
                    nav.navigate(Routes.AUDIO_UNSUPPORTED)
                },
            )
        }
    }
}
