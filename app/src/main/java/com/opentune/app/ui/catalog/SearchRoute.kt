package com.opentune.app.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    scopeLocationEncoded: String,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }
    var instance by remember { mutableStateOf<OpenTuneProviderInstance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<MediaListItem>() }
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    LaunchedEffect(providerType, sourceId) {
        try {
            instance = app.instanceRegistry.getOrCreate(sourceId)
                ?: throw IllegalStateException("No instance for $sourceId")
        } catch (e: Exception) {
            error = e.message
        }
    }

    val coverExtractor = rememberCoverExtractor(app, providerType, sourceId, instance, results)

    when {
        error != null -> Text("Error: $error")
        instance == null -> Text("Loading\u2026")
        else -> {
            val inst = instance!!
            SearchScreen(
                logTag = "OT_Search_$sourceId",
                results = results,
                searchFn = { query -> inst.searchItems(scopeDecoded, query) },
                titleLang = titleLang,
                onBack = { nav.popBackStack() },
                onOpenBrowse = { raw -> nav.navigate(Routes.browse(providerType, sourceId, raw)) },
                onOpenDetail = { raw -> nav.navigate(Routes.detail(providerType, sourceId, raw)) },
                onItemsLoaded = coverExtractor.onItemsLoaded,
            )
        }
    }
}
