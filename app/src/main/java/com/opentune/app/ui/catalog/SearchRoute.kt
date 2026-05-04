package com.opentune.app.ui.catalog

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
import com.opentune.storage.OpenTuneDatabase
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    scopeLocationEncoded: String,
) {
    val scopeDecoded = remember(scopeLocationEncoded) { CatalogNav.decodeSegment(scopeLocationEncoded) }
    var state by remember { mutableStateOf<CatalogScreenBindingState>(CatalogScreenBindingState.Loading) }

    LaunchedEffect(app, database, providerId, sourceId, scopeDecoded) {
        state = CatalogScreenBindingState.Loading
        state = resolveSearchBinding(app, database, providerId, sourceId, scopeDecoded).toScreenState()
    }

    DisposableEffect(state) {
        val b = (state as? CatalogScreenBindingState.Ready)?.binding
        onDispose { b?.onDispose() }
    }

    when (val s = state) {
        is CatalogScreenBindingState.Loading -> Text("Loading…")
        is CatalogScreenBindingState.Error -> Text("Error: ${s.message}")
        is CatalogScreenBindingState.Ready -> {
            val b = s.binding
            SearchScreen(
                logTag = b.logTag,
                catalog = b.catalog,
                onBack = { nav.popBackStack() },
                onOpenBrowse = { raw -> nav.navigate(Routes.browse(providerId, sourceId, raw)) },
                onOpenDetail = { raw -> nav.navigate(Routes.detail(providerId, sourceId, raw)) },
            )
        }
    }
}
