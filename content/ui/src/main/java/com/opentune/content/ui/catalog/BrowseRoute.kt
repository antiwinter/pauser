package com.opentune.content.ui.catalog
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.storage.StorageBindingsHolder

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.ui.Routes
import com.opentune.content.ui.toJson
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.QueryOptions
import com.opentune.storage.TitleLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OpenTuneBrowseRoute"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    protocol: String,
    endpointId: String,
    initialEntryInfo: EntryInfo,
    viewModel: BrowseViewModel,
    sharedVm: NavSharedViewModel,
) {
    val location = initialEntryInfo.id
    val collectionType = initialEntryInfo.collectionType

    val items = remember { mutableStateListOf<EntryInfo>() }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var client by remember { mutableStateOf<EndpointClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Observe ViewModel state
    val vmItems by viewModel.items.collectAsState()
    val vmError by viewModel.error.collectAsState()

    // Sync ViewModel items into SnapshotStateList (survives back navigation)
    LaunchedEffect(vmItems) {
        items.clear()
        items.addAll(vmItems)
    }

    LaunchedEffect(protocol, endpointId) {
        error = null
        val existing = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
        if (existing == null) {
            Log.e(LOG_TAG, "No instance for endpointId=$endpointId")
            error = "Endpoint not found"
        } else {
            client = existing
        }
    }

    // Build query options from the passed-in collectionType.
    // When navigating from a library root, collectionType is carried in EntryInfo.
    // When navigating from debug API without it, the backend auto-detects via getEntries.
    val queryOptions = remember(collectionType) {
        when (collectionType?.lowercase()) {
            "movies" -> QueryOptions(recursive = true, filterByType = "Movie")
            else -> QueryOptions()
        }
    }

    // Initialize ViewModel with client once resolved; load if data is stale.
    LaunchedEffect(client, queryOptions) {
        val c = client ?: return@LaunchedEffect
        viewModel.initialize(c, queryOptions, protocol, endpointId)
        viewModel.load()
    }

    // Sync VM error to local error
    LaunchedEffect(vmError) {
        error = vmError
    }

    when {
        error != null -> Text("Error: $error")
        client == null -> Text("Loading…")
        else -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            imageLoader = client!!.imageLoader!!,
            loadPage = { startIndex, limit ->
                withContext(Dispatchers.IO) {
                    client!!.listEntry(location, startIndex, limit, queryOptions)
                }.let { result ->
                    result.copy(items = ArtUrlInjector.apply(result.items, protocol, endpointId))
                }
            },
            subtitle = initialEntryInfo.title,
            titleLang = titleLang,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(protocol, endpointId, location)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onOpenBrowseLocation = { folderEntry ->
                sharedVm.cache(folderEntry)
                nav.navigate(Routes.browse(protocol, endpointId, folderEntry))
            },
            onOpenDetail = { item -> nav.navigate(Routes.detail(protocol, endpointId, item.id, item.toJson())) },
            onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) },
            onOpenImageViewer = { raw -> nav.navigate(Routes.imageViewer(protocol, endpointId, raw)) },
        )
    }
}
