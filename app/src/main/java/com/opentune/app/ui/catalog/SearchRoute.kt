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
import com.opentune.app.navigation.toJson
import com.opentune.provider.EndpointClient
import com.opentune.provider.EntryInfo
import com.opentune.provider.SearchQuery
import com.opentune.storage.TitleLang

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    protocol: String,
    endpointId: String,
    scopeLocationEncoded: String,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }
    var client by remember { mutableStateOf<EndpointClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<EntryInfo>() }
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    LaunchedEffect(protocol, endpointId) {
        try {
            client = app.endpointClientRegistry.getOrCreate(endpointId)
                ?: throw IllegalStateException("No instance for $endpointId")
        } catch (e: Exception) {
            error = e.message
        }
    }

    when {
        error != null -> Text("Error: $error")
        client == null -> Text("Loading…")
        else -> {
            val c = client!!
            SearchScreen(
                logTag = "OT_Search_$endpointId",
                results = results,
                imageLoader = c.imageLoader,
                searchFn = { query ->
                    c.search(scopeDecoded, SearchQuery(term = query)).items
                        .let { ArtUrlInjector.apply(it, app, protocol, endpointId) }
                },
                titleLang = titleLang,
                onBack = { nav.popBackStack() },
                onOpenBrowse = { raw -> nav.navigate(Routes.browse(protocol, endpointId, raw)) },
                onOpenDetail = { item -> nav.navigate(Routes.detail(protocol, endpointId, item.id, item.toJson())) },
                onOpenPlayer = { raw, startMs -> nav.navigate(Routes.player(protocol, endpointId, raw, startMs ?: 0L)) },
            )
        }
    }
}
