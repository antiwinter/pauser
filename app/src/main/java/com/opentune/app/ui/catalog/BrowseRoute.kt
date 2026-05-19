package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.EntryType
import com.opentune.provider.EntryInfo
import com.opentune.provider.EndpointClient
import com.opentune.storage.TitleLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OpenTuneBrowseRoute"

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Error(val message: String) : BrowseState
    data class Ready(val instance: EndpointClient) : BrowseState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    protocol: String,
    endpointId: String,
    locationEncoded: String,
) {
    val locationDecoded = remember(locationEncoded) { CatalogNav.decodeSegment(locationEncoded) }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    val items = remember { mutableStateListOf<EntryInfo>() }
    val scope = rememberCoroutineScope()
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    LaunchedEffect(app, protocol, endpointId) {
        state = BrowseState.Loading
        items.clear()
        val instance = app.endpointClientRegistry.getOrCreate(endpointId)
        state = if (instance == null) {
            Log.e(LOG_TAG, "No instance for endpointId=$endpointId")
            BrowseState.Error("Endpoint not found")
        } else {
            BrowseState.Ready(instance)
        }
    }

    val instance = (state as? BrowseState.Ready)?.instance

    when (val s = state) {
        is BrowseState.Loading -> Text("Loading…")
        is BrowseState.Error -> Text("Error: ${s.message}")
        is BrowseState.Ready -> BrowseScreen(
            logTag = "OT_Browse_$endpointId",
            items = items,
            loadPage = { startIndex, limit ->
                withContext(Dispatchers.IO) {
                    s.instance.listEntry(locationDecoded.ifEmpty { null }, startIndex, limit)
                }.let { result ->
                    result.copy(items = ArtUrlInjector.apply(result.items, app, protocol, endpointId))
                }
            },
            subtitle = locationDecoded,
            titleLang = titleLang,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(protocol, endpointId, locationDecoded)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onOpenBrowseLocation = { folderId ->
                scope.launch {
                    try {
                        val page = withContext(Dispatchers.IO) {
                            s.instance.listEntry(folderId, 0, 1)
                        }
                        val firstItem = page.items.firstOrNull()
                        if (page.totalCount == 1 && firstItem?.type == EntryType.Playable) {
                            nav.navigate(Routes.detail(protocol, endpointId, firstItem.id))
                        } else {
                            nav.navigate(Routes.browse(protocol, endpointId, folderId))
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "auto-nav check failed for $folderId", e)
                        nav.navigate(Routes.browse(protocol, endpointId, folderId))
                    }
                }
            },
            onOpenDetail = { raw -> nav.navigate(Routes.detail(protocol, endpointId, raw)) },
            onOpenImageViewer = { raw -> nav.navigate(Routes.imageViewer(protocol, endpointId, raw)) },
        )
    }
}
