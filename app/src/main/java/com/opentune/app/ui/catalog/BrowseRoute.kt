package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.TitleLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

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
            titleLang = titleLang,
            onBack = { nav.popBackStack() },
            onSearch = { nav.navigate(Routes.search(providerType, sourceId, locationDecoded)) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onOpenBrowseLocation = { folderId ->
                scope.launch {
                    try {
                        val page = withContext(Dispatchers.IO) {
                            s.instance.loadBrowsePage(folderId, 0, 1)
                        }
                        val firstItem = page.items.firstOrNull()
                        if (page.totalCount == 1 && firstItem?.kind == MediaEntryKind.Playable) {
                            nav.navigate(Routes.detail(providerType, sourceId, firstItem.id))
                        } else {
                            nav.navigate(Routes.browse(providerType, sourceId, folderId))
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "auto-nav check failed for $folderId", e)
                        nav.navigate(Routes.browse(providerType, sourceId, folderId))
                    }
                }
            },
            onOpenDetail = { raw -> nav.navigate(Routes.detail(providerType, sourceId, raw)) },
            onItemsLoaded = coverExtractor.onItemsLoaded,
        )
    }
}
