package com.opentune.content.ui.catalog.browse

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.QueryOptions
import com.opentune.content.ui.Routes
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.ArtUrlInjector
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController

private const val LOG_TAG = "OpenTuneBrowseRoute"

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
    val location = initialEntryInfo.id

    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var client by remember { mutableStateOf<EndpointClient?>(null) }

    val vmItems by viewModel.items.collectAsState()
    val vmLoading by viewModel.loading.collectAsState()
    val vmError by viewModel.error.collectAsState()
    val vmTotal by viewModel.totalCount.collectAsState()
    val vmLastFocusedItemId by viewModel.lastFocusedItemId.collectAsState()

    val items = remember { mutableStateListOf<EntryInfo>() }
    LaunchedEffect(vmItems) {
        if (vmItems.isNotEmpty() && items.isEmpty()) {
            items.addAll(vmItems)
        }
    }

    LaunchedEffect(endpointId) {
        val existing = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
        if (existing == null) {
            Log.e(LOG_TAG, "No instance for endpointId=$endpointId")
        } else {
            client = existing
        }
    }

    val queryOptions = remember { QueryOptions() }

    LaunchedEffect(client, queryOptions) {
        val c = client ?: return@LaunchedEffect
        Log.d(LOG_TAG, "init+load for location=$location, client=$c, endpointId=$endpointId")
        viewModel.initialize(c, queryOptions, c.protocol, endpointId)
        viewModel.load()
    }

    val c = client

    when {
        client == null -> Text("Loading…")
        else -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            loadMore = { startIndex, limit ->
                c.listEntry(location, startIndex, limit, queryOptions)
                    .let { result ->
                        result.copy(items = ArtUrlInjector.apply(result.items, c.protocol, endpointId))
                    }
            },
            subtitle = initialEntryInfo.title,
            titleLang = titleLang,
            imageLoader = c!!.imageLoader!!,
            totalCount = vmTotal,
            loading = vmLoading,
            error = vmError,
            initialFocusId = vmLastFocusedItemId,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(endpointId, location)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onItemFocused = { item -> viewModel.setlastFocusedItemId(item.id) },
            onOpenBrowseLocation = { folderEntry ->
                sharedVm.cache(folderEntry)
                nav.navigate(Routes.browse(endpointId, folderEntry))
            },
            onOpenDetail = { item ->
                sharedVm.cache(item)
                nav.navigate(Routes.detail(endpointId, item))
            },
            onOpenPlayer = { entry ->
                val clientRef = c ?: return@BrowseScreen
                playerController.setClient(clientRef)
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
