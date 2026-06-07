package com.opentune.content.ui.catalog

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

private const val LOG_TAG = "OpenTuneBrowseRoute"

/**
 * Browse route entry point.
 *
 * Lifecycle:
 * 1. ViewModel is scoped to the back stack entry — survives navigation back/forward
 * 2. On first visit: ViewModel.load() fetches from network
 * 3. On return visit: ViewModel.load() sees items already present → skips fetch
 *    → BrowseScreen renders cached items immediately
 * 4. Data layer (CachingEndpointClient) handles network dedup and stale detection
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    protocol: String,
    endpointId: String,
    initialEntryInfo: EntryInfo,
    viewModel: BrowseViewModel,
    sharedVm: NavSharedViewModel,
    playerController: PlayerController,
) {
    val location = initialEntryInfo.id
    val collectionType = initialEntryInfo.collectionType

    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var client by remember { mutableStateOf<EndpointClient?>(null) }

    // Observe ViewModel state
    val vmItems by viewModel.items.collectAsState()
    val vmLoading by viewModel.loading.collectAsState()
    val vmError by viewModel.error.collectAsState()
    val vmTotal by viewModel.totalCount.collectAsState()

    // Sync ViewModel items into SnapshotStateList for LazyVerticalGrid.
    // NEVER clear items that are already present — this preserves the display
    // when navigating back (ViewModel has items, Screen shows them immediately).
    val items = remember { mutableStateListOf<EntryInfo>() }
    LaunchedEffect(vmItems) {
        if (vmItems.isNotEmpty() && items.isEmpty()) {
            items.addAll(vmItems)
        }
    }

    // Resolve the EndpointClient (which is already a CachingEndpointClient from the registry).
    LaunchedEffect(protocol, endpointId) {
        val existing = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
        if (existing == null) {
            Log.e(LOG_TAG, "No instance for endpointId=$endpointId")
        } else {
            client = existing
        }
    }

    // Build query options from the passed-in collectionType.
    val queryOptions = remember(collectionType) {
        when (collectionType?.lowercase()) {
            "movies" -> QueryOptions(recursive = true, filterByType = "Movie")
            else -> QueryOptions()
        }
    }

    // Initialize ViewModel with client once resolved; load if items are empty.
    LaunchedEffect(client, queryOptions) {
        val c = client ?: return@LaunchedEffect
        Log.d(LOG_TAG, "init+load for location=$location, client=$c, protocol=$protocol, endpointId=$endpointId")
        viewModel.initialize(c, queryOptions, protocol, endpointId)
        viewModel.load()
    }

    val c = client
    val exoPlayer = playerController.exoPlayer

    // Player overlay — full-screen when ExoPlayer is active
    if (exoPlayer != null) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            PlayerSurface(
                exoPlayer = exoPlayer,
                startMs = playerController.startMs,
                onBack = {
                    playerController.pause()
                    playerController.release()
                    Log.d(LOG_TAG, "player overlay: back → pause & release")
                },
            )
        }
        return
    }

    when {
        client == null -> Text("Loading…")
        else -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            loadMore = { startIndex, limit ->
                c.listEntry(location, startIndex, limit, queryOptions)
                    .let { result ->
                        result.copy(items = ArtUrlInjector.apply(result.items, protocol, endpointId))
                    }
            },
            subtitle = initialEntryInfo.title,
            titleLang = titleLang,
            imageLoader = c!!.imageLoader!!,
            totalCount = vmTotal,
            loading = vmLoading,
            error = vmError,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(protocol, endpointId, location)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onOpenBrowseLocation = { folderEntry ->
                sharedVm.cache(folderEntry)
                nav.navigate(Routes.browse(protocol, endpointId, folderEntry))
            },
            onOpenDetail = { item ->
                sharedVm.cache(item)
                nav.navigate(Routes.detail(protocol, endpointId, item.id, item))
            },
            onOpenPlayer = { raw, startMs ->
                val clientRef = c ?: return@BrowseScreen
                playerController.setItem(raw, clientRef, startMs ?: 0L)
            },
            onOpenImageViewer = { raw -> nav.navigate(Routes.imageViewer(protocol, endpointId, raw)) },
        )
    }
}
