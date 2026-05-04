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
import com.opentune.provider.CatalogScreenBindingState
import com.opentune.provider.toScreenState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerId: String,
    sourceId: Long,
    locationEncoded: String,
) {
    val locationDecoded = remember(locationEncoded) { CatalogNav.decodeSegment(locationEncoded) }
    var state by remember { mutableStateOf<CatalogScreenBindingState>(CatalogScreenBindingState.Loading) }

    LaunchedEffect(app, providerId, sourceId, locationDecoded) {
        state = CatalogScreenBindingState.Loading
        state = resolveBrowseBinding(app, providerId, sourceId, locationDecoded).toScreenState()
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
            BrowseScreen(
                logTag = b.logTag,
                catalog = b.catalog,
                subtitle = b.subtitle,
                onBack = { nav.popBackStack() },
                onSearch = {
                    nav.navigate(Routes.search(providerId, sourceId, locationDecoded))
                },
                onOpenBrowseLocation = { raw ->
                    nav.navigate(Routes.browse(providerId, sourceId, raw))
                },
                onOpenDetail = { raw ->
                    nav.navigate(Routes.detail(providerId, sourceId, raw))
                },
            )
        }
    }
}
