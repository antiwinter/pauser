package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private const val LOG_TAG = "OpenTuneBrowseRoute"

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Error(val message: String) : BrowseState
    data class Ready(val instance: com.opentune.provider.OpenTuneProviderInstance) : BrowseState
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

    LaunchedEffect(app, providerType, sourceId) {
        state = BrowseState.Loading
        val instance = app.instanceRegistry.getOrCreate(sourceId)
        state = if (instance == null) {
            Log.e(LOG_TAG, "No instance for sourceId=$sourceId")
            BrowseState.Error("Server not found")
        } else {
            BrowseState.Ready(instance)
        }
    }

    when (val s = state) {
        is BrowseState.Loading -> Text("Loading\u2026")
        is BrowseState.Error -> Text("Error: ${s.message}")
        is BrowseState.Ready -> {
            val instance = s.instance
            BrowseScreen(
                logTag = "OpenTuneEmbyBrowse",
                loadPage = { startIndex, limit -> instance.loadBrowsePage(locationDecoded, startIndex, limit) },
                subtitle = if (locationDecoded == CatalogNav.LIBRARIES_ROOT_SEGMENT) "Libraries" else locationDecoded,
                onBack = { nav.popBackStack() },
                onSearch = {
                    nav.navigate(Routes.search(providerType, sourceId, locationDecoded))
                },
                onOpenBrowseLocation = { raw ->
                    nav.navigate(Routes.browse(providerType, sourceId, raw))
                },
                onOpenDetail = { raw ->
                    nav.navigate(Routes.detail(providerType, sourceId, raw))
                },
            )
        }
    }
}
