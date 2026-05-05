package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance

private const val LOG_TAG = "OpenTuneBrowseRoute"

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Error(val message: String) : BrowseState
    data class Ready(val instance: OpenTuneProviderInstance) : BrowseState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    locationEncoded: String,
) {
    val locationDecoded = remember(locationEncoded) { CatalogNav.decodeSegment(locationEncoded) }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    val items = remember { mutableStateListOf<MediaListItem>() }

    LaunchedEffect(app, providerType, sourceId) {
        state = BrowseState.Loading
        items.clear()
        val instance = app.instanceRegistry.getOrCreate(sourceId)
        state = if (instance == null) {
            Log.e(LOG_TAG, "No instance for sourceId=$sourceId")
            BrowseState.Error("Server not found")
        } else {
            BrowseState.Ready(instance)
        }
    }

    val instance = (state as? BrowseState.Ready)?.instance
    val coverExtractor = rememberCoverExtractor(app, providerType, sourceId, instance, items)

    when (val s = state) {
        is BrowseState.Loading -> Text("Loading\u2026")
        is BrowseState.Error -> Text("Error: ${s.message}")
        is BrowseState.Ready -> BrowseScreen(
            logTag = "OT_Browse_$sourceId",
            items = items,
            loadPage = { startIndex, limit -> s.instance.loadBrowsePage(locationDecoded, startIndex, limit) },
            subtitle = if (locationDecoded == CatalogNav.LIBRARIES_ROOT_SEGMENT) "Libraries" else locationDecoded,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(providerType, sourceId, locationDecoded)) },
            onOpenBrowseLocation = { raw -> nav.navigate(Routes.browse(providerType, sourceId, raw)) },
            onOpenDetail = { raw -> nav.navigate(Routes.detail(providerType, sourceId, raw)) },
            onItemsLoaded = coverExtractor.onItemsLoaded,
        )
    }
}
