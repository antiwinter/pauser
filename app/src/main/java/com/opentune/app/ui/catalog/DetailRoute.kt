package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    itemRefEncoded: String,
) {
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CatalogScreenBindingState>(CatalogScreenBindingState.Loading) }

    LaunchedEffect(app, database, providerId, sourceId, itemRefDecoded) {
        state = CatalogScreenBindingState.Loading
        state = resolveDetailBinding(app, database, providerId, sourceId, itemRefDecoded).toScreenState()
    }

    DisposableEffect(state) {
        val b = (state as? CatalogScreenBindingState.Ready)?.binding
        onDispose { b?.onDispose() }
    }

    var detail by remember { mutableStateOf<MediaDetailModel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val readyBinding = (state as? CatalogScreenBindingState.Ready)?.binding
    LaunchedEffect(readyBinding?.catalog, itemRefDecoded, readyBinding?.logTag) {
        val c = readyBinding?.catalog ?: return@LaunchedEffect
        val logTag = readyBinding.logTag
        loading = true
        error = null
        try {
            detail = withContext(Dispatchers.IO) { c.loadDetail(itemRefDecoded) }
        } catch (e: Exception) {
            Log.e(logTag, "detail", e)
            error = e.message
            detail = null
        } finally {
            loading = false
        }
    }

    when (val s = state) {
        is CatalogScreenBindingState.Loading -> Text("Loading…")
        is CatalogScreenBindingState.Error -> Text("Error: ${s.message}")
        is CatalogScreenBindingState.Ready -> {
            val c = s.binding.catalog
            DetailScreen(
                detail = detail,
                loading = loading,
                error = error,
                onBack = { nav.popBackStack() },
                onPlayFromStart = {
                    nav.navigate(Routes.player(providerId, sourceId, itemRefDecoded, 0L))
                },
                onResume = {
                    val ms = detail?.resumePositionMs ?: 0L
                    nav.navigate(Routes.player(providerId, sourceId, itemRefDecoded, ms))
                },
                onToggleFavorite = {
                    val d = detail
                    if (d != null && d.favoriteSupported) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    c.setFavorite(itemRefDecoded, !d.isFavorite)
                                }
                                detail = withContext(Dispatchers.IO) { c.loadDetail(itemRefDecoded) }
                            } catch (e: Exception) {
                                Log.e(s.binding.logTag, "favorite", e)
                                error = e.message
                            }
                        }
                    }
                },
            )
        }
    }
}
