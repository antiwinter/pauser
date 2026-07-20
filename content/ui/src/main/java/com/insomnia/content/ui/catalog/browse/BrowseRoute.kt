package com.insomnia.content.ui.catalog.browse

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.insomnia.content.contract.QueryOptions
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
    initialSpecs: List<QuerySpec>,
    viewModel: BrowseViewModel,
    sharedVm: NavSharedViewModel,
    playerController: PlayerController,
) {
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    val queries by viewModel.queries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val restoreFocusRef by viewModel.lastFocusedItemRef.collectAsState()

    BackHandler { nav.popBackStack() }
    LaunchedEffect(initialSpecs) {
        viewModel.initialize(initialSpecs)
    }

    PlayerStopEffect(playerController) {
        viewModel.refresh()
    }


    when {
        queries.isEmpty() -> Text("No results")
        else -> {
            val coroutineScope = rememberCoroutineScope()
            BrowseScreen(
                results = queries,
                loading = loading,
                error = error,
                titleLang = titleLang,
                initialFocusRef = restoreFocusRef,
                onLoadMore = { viewModel.loadMore() },
                onSearch = { term, scope ->
                    coroutineScope.launch {
                        val searchSpecs = viewModel.buildSearchQuerySpec(term, scope)
                        if (searchSpecs.isNotEmpty()) {
                            nav.navigate(Routes.browse(searchSpecs))
                        }
                    }
                },
                onItemFocused = { queryIndex, itemIndex -> viewModel.setLastFocusedItem(queryIndex, itemIndex) },
                onNavigateToPath = { spec ->
                    nav.navigate(Routes.browse(listOf(spec)))
                },
                onOpenBrowseLocation = { endpointId, folderEntry ->
                    val spec = QuerySpec(endpointId, folderEntry.ref, QueryOptions())
                    nav.navigate(Routes.browse(listOf(spec)))
                },
                onOpenDetail = { endpointId, item ->
                    sharedVm.cache(item)
                    nav.navigate(Routes.detail(endpointId, item))
                },
                onOpenLivePlayer = { endpointId, item ->
                    sharedVm.cache(item)
                    nav.navigate(Routes.livePlayer(endpointId, item))
                },
                onOpenPlayer = { endpointId, entry ->
                    val itemClient = queries.firstOrNull { it.spec.endpointId == endpointId }?.client
                        ?: throw IllegalStateException("No client for endpoint $endpointId")
                    playerController.setClient(itemClient)
                    val startMs = if (entry.type == "LiveChannel") 0L else null
                    playerController.prepare(entry, startMs)
                    playerController.play()
                },
                onOpenAudioUnsupported = { endpointId, raw ->
                    nav.navigate(Routes.AUDIO_UNSUPPORTED)
                },
            )
        }
    }
}
