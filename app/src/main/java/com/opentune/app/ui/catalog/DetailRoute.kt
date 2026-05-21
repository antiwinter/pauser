package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.app.OpenTuneApplication
import com.opentune.app.image.ProxyImageLoader
import com.opentune.app.navigation.Routes
import com.opentune.storage.EntryStateKey
import com.opentune.storage.TitleLang
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    protocol: String,
    endpointId: String,
    itemRefEncoded: String,
    initialInfo: EntryInfo? = null,
) {
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(protocol, endpointId, itemRefDecoded) {
        EntryStateKey(protocol, endpointId, itemRefDecoded)
    }
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var detail by remember { mutableStateOf<EntryDetail?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var resumeMs by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var imageLoader by remember { mutableStateOf<ImageLoader?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Series/season state
    var seasons by remember { mutableStateOf<List<EntryInfo>?>(null) }
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<EntryInfo>>(emptyList()) }
    var totalEpisodes by remember { mutableIntStateOf(0) }
    var episodePage by remember { mutableIntStateOf(0) }

    // Digipak children state
    val digipakChildren = remember { mutableStateListOf<EntryInfo>() }
    var singleChild by remember { mutableStateOf<EntryInfo?>(null) }

    // Load detail + children
    LaunchedEffect(protocol, endpointId, itemRefDecoded) {
        loading = true
        error = null
        seasons = null
        selectedSeasonIndex = 0
        episodes = emptyList()
        episodePage = 0
        digipakChildren.clear()
        singleChild = null
        try {
            val handle = app.endpointClientRegistry.getOrCreate(endpointId)
                ?: throw IllegalStateException("No provider instance for $endpointId")
            val inst = handle.client
            imageLoader = ProxyImageLoader.get(endpointId, handle.httpClient, app)
            val entryState = withContext(Dispatchers.IO) {
                app.storageBindings.entryStateStore.get(stateKey)
            }
            isFavorite = entryState?.isFavorite ?: false
            resumeMs = entryState?.positionMs ?: 0L
            val d = withContext(Dispatchers.IO) { inst.getDetail(itemRefDecoded) }
            detail = ArtUrlInjector.applyDetail(d, app, protocol)

            // Fetch children based on type
            when (initialInfo?.type) {
                EntryType.Series -> {
                    val result = withContext(Dispatchers.IO) {
                        inst.listEntry(itemRefDecoded, 0, 500)
                    }
                    seasons = ArtUrlInjector.apply(result.items, app, protocol, endpointId)
                }
                EntryType.Digipak -> {
                    val childCount = initialInfo.childCount ?: 0
                    val result = withContext(Dispatchers.IO) {
                        inst.listEntry(itemRefDecoded, 0, maxOf(childCount, 1))
                    }
                    val filtered = result.items
                    if (childCount <= 1 && filtered.isNotEmpty()) {
                        singleChild = filtered.first()
                    } else {
                        digipakChildren.addAll(
                            ArtUrlInjector.apply(filtered, app, protocol, endpointId, ArtType.Thumb)
                        )
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "detail load", e)
            error = e.message
        } finally {
            loading = false
        }
    }

    // Load episodes when season changes (Series)
    LaunchedEffect(seasons, selectedSeasonIndex, episodePage) {
        val seasonList = seasons ?: return@LaunchedEffect
        val season = seasonList.getOrNull(selectedSeasonIndex) ?: return@LaunchedEffect
        try {
            val inst = app.endpointClientRegistry.getOrCreate(endpointId)?.client ?: return@LaunchedEffect
            val result = withContext(Dispatchers.IO) {
                inst.listEntry(season.id, episodePage * 50, 50)
            }
            episodes = ArtUrlInjector.apply(result.items, app, protocol, endpointId, ArtType.Thumb)
                .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            totalEpisodes = result.totalCount
        } catch (e: Exception) {
            Log.e(LOG_TAG, "episodes load", e)
        }
    }

    when {
        error != null -> Text("Error: $error")
        else -> DetailScreen(
            initialInfo = initialInfo,
            detail = detail,
            loading = loading,
            isFavorite = isFavorite,
            resumeMs = resumeMs,
            titleLang = titleLang,
            seasons = seasons,
            selectedSeasonIndex = selectedSeasonIndex,
            episodes = episodes,
            totalEpisodes = totalEpisodes,
            episodePage = episodePage,
            children = digipakChildren.toList(),
            singleChild = singleChild,
            onBack = { nav.popBackStack() },
            onPlayFromStart = {
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, 0L))
            },
            onResume = {
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, resumeMs))
            },
            onToggleFavorite = {
                scope.launch {
                    val newVal = !isFavorite
                    isFavorite = newVal
                    try {
                        withContext(Dispatchers.IO) {
                            app.storageBindings.entryStateStore.upsertFavorite(stateKey, newVal)
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "favorite toggle", e)
                        isFavorite = !newVal
                    }
                }
            },
            onSelectSeason = { index -> selectedSeasonIndex = index; episodePage = 0 },
            onSelectEpisode = { episode ->
                val startMs = episode.userData?.positionMs ?: 0L
                nav.navigate(Routes.player(protocol, endpointId, episode.id, startMs, episode))
            },
            onSelectPage = { page -> episodePage = page },
            onSelectChild = { child ->
                val startMs = child.userData?.positionMs ?: 0L
                nav.navigate(Routes.player(protocol, endpointId, child.id, startMs, child))
            },
            onPlaySingleChild = {
                val child = singleChild ?: return@DetailScreen
                val startMs = child.userData?.positionMs ?: 0L
                nav.navigate(Routes.player(protocol, endpointId, child.id, startMs, child))
            },
        )
    }
}
