package com.opentune.content.ui.catalog
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.storage.StorageBindingsHolder

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.ImageLoader
import com.opentune.content.ui.Routes
import com.opentune.storage.EntryStateKey
import com.opentune.storage.TitleLang
import com.opentune.storage.decodeSeriesProgress
import com.opentune.content.contract.EntryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
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
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var entryInfo by remember { mutableStateOf<EntryInfo?>(initialInfo) }
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

    // Load entry info + children
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
            val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                ?: throw IllegalStateException("No provider instance for $endpointId")
            imageLoader = client.imageLoader
            val entryState = withContext(Dispatchers.IO) {
                StorageBindingsHolder.get().entryStateStore.get(stateKey)
            }
            isFavorite = entryState?.isFavorite ?: false

            // Resolve resume: for series, the positionMs may be packed (season, episode)
            val rawPosition = entryState?.positionMs ?: 0L
            val info = initialInfo ?: run {
                val result = withContext(Dispatchers.IO) {
                    client.listEntry(itemRefDecoded, 0, 1)
                }
                result.items.firstOrNull()
            }

            // For series, resolve which season/episode the user was on
            val resolvedResumeMs = if (info?.type == "Series" && entryState != null) {
                val (season, episode) = decodeSeriesProgress(rawPosition)
                if (season > 0) {
                    selectedSeasonIndex = maxOf(0, season - 1)
                }
                rawPosition
            } else {
                rawPosition
            }
            resumeMs = resolvedResumeMs

            entryInfo = info?.let { ArtUrlInjector.applyInfo(it, protocol) }

            // Fetch children based on type
            when (entryInfo?.type) {
                "Series" -> {
                    val result = withContext(Dispatchers.IO) {
                        client.listEntry(itemRefDecoded, 0, 500)
                    }
                    seasons = ArtUrlInjector.apply(result.items, protocol, endpointId)
                }
                "Digipak" -> {
                    val childCount = entryInfo?.childCount ?: 0
                    val result = withContext(Dispatchers.IO) {
                        client.listEntry(itemRefDecoded, 0, maxOf(childCount, 1))
                    }
                    val filtered = result.items
                    if (childCount <= 1 && filtered.isNotEmpty()) {
                        singleChild = filtered.first()
                    } else {
                        digipakChildren.addAll(
                            ArtUrlInjector.apply(filtered, protocol, endpointId, ArtType.Thumb)
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
            val inst = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
            val result = withContext(Dispatchers.IO) {
                inst.listEntry(season.id, episodePage * 50, 50)
            }
            episodes = ArtUrlInjector.apply(result.items, protocol, endpointId, ArtType.Thumb)
                .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            totalEpisodes = result.totalCount
        } catch (e: Exception) {
            Log.e(LOG_TAG, "episodes load", e)
        }
    }

    val loader = imageLoader
    when {
        error != null -> Text("Error: $error")
        loading || loader == null || entryInfo == null -> Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { CircularProgressIndicator() }
        else -> {
            val info = entryInfo!!
            val playFromStart = {
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, 0L, info))
            }
            val resumePlay = {
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, resumeMs, info))
            }
            val toggleFav = {
                scope.launch {
                    val newVal = !isFavorite
                    isFavorite = newVal
                    try {
                        withContext(Dispatchers.IO) {
                            StorageBindingsHolder.get().entryStateStore.upsertFavorite(stateKey, newVal)
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "favorite toggle", e)
                        isFavorite = !newVal
                    }
                }
                Unit
            }
            val selectSeason = { index: Int -> selectedSeasonIndex = index; episodePage = 0 }
            val selectEpisode = { episode: EntryInfo ->
                val startMs = episode.userData?.positionMs ?: 0L
                nav.navigate(Routes.player(protocol, endpointId, episode.id, startMs, episode))
            }
            val selectPage = { page: Int -> episodePage = page }
            val selectChild = { child: EntryInfo ->
                val startMs = child.userData?.positionMs ?: 0L
                nav.navigate(Routes.player(protocol, endpointId, child.id, startMs, child))
            }
            val playSingleChild: () -> Unit = run {
                val child = singleChild
                {
                    if (child != null) {
                        val startMs = child.userData?.positionMs ?: 0L
                        nav.navigate(Routes.player(protocol, endpointId, child.id, startMs, child))
                    }
                }
            }

            when (info.type) {
                "Movie", "Playable", "Episode", "Video" -> MovieOverviewScreen(
                    entryInfo = info,
                    titleLang = titleLang,
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    onResume = resumePlay,
                    onPlayFromStart = playFromStart,
                    onToggleFavorite = toggleFav,
                )
                "Series" -> SeriesOverviewScreen(
                    entryInfo = info,
                    titleLang = titleLang,
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    seasons = seasons ?: emptyList(),
                    selectedSeasonIndex = selectedSeasonIndex,
                    episodes = episodes,
                    totalEpisodes = totalEpisodes,
                    episodePage = episodePage,
                    imageLoader = loader,
                    onResume = resumePlay,
                    onPlayFromStart = playFromStart,
                    onToggleFavorite = toggleFav,
                    onSelectSeason = selectSeason,
                    onSelectEpisode = selectEpisode,
                    onSelectPage = selectPage,
                )
                "Digipak" -> DigipakOverviewScreen(
                    entryInfo = info,
                    titleLang = titleLang,
                    resumeMs = resumeMs,
                    isFavorite = isFavorite,
                    children = digipakChildren.toList(),
                    singleChild = singleChild,
                    imageLoader = loader,
                    onResume = resumePlay,
                    onPlayFromStart = playFromStart,
                    onToggleFavorite = toggleFav,
                    onPlaySingleChild = playSingleChild,
                    onSelectChild = selectChild,
                )
                else -> Text("Unsupported type: ${info.type}")
            }
        }
    }
}
