package com.opentune.content.ui.catalog

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.storage.decodeSeriesProgress
import com.opentune.content.ui.Routes
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
    sharedVm: NavSharedViewModel,
    viewModel: DetailViewModel,
) {
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(protocol, endpointId, itemRefDecoded) {
        EntryStateKey(protocol, endpointId, itemRefDecoded)
    }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    // Observe ViewModel state
    val vmEntryInfo by viewModel.entryInfo.collectAsState()
    val vmSeasons by viewModel.seasons.collectAsState()
    val vmSelectedSeasonIndex by viewModel.selectedSeasonIndex.collectAsState()
    val vmEpisodes by viewModel.episodes.collectAsState()
    val vmTotalEpisodes by viewModel.totalEpisodes.collectAsState()
    val vmEpisodePage by viewModel.episodePage.collectAsState()
    val vmDigipakChildren by viewModel.digipakChildren.collectAsState()
    val vmSingleChild by viewModel.singleChild.collectAsState()
    val vmLoading by viewModel.loading.collectAsState()
    val vmError by viewModel.error.collectAsState()

    // Resolve image loader + entry state (favorite, resume position)
    var imageLoader by remember { mutableStateOf<coil3.ImageLoader?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var resumeMs by remember { mutableStateOf(0L) }

    LaunchedEffect(protocol, endpointId) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
            ?: throw IllegalStateException("No provider instance for $endpointId")
        imageLoader = client.imageLoader
        val entryState = withContext(Dispatchers.IO) {
            StorageBindingsHolder.get().entryStateStore.get(stateKey)
        }
        isFavorite = entryState?.isFavorite ?: false

        val rawPosition = entryState?.positionMs ?: 0L
        val info = initialInfo
        val resolvedResumeMs = if (info?.type == "Series" && entryState != null) {
            val (season) = decodeSeriesProgress(rawPosition)
            if (season > 0) viewModel.selectSeason(maxOf(0, season - 1))
            rawPosition
        } else {
            rawPosition
        }
        resumeMs = resolvedResumeMs
    }

    // Initialize ViewModel and trigger loads
    LaunchedEffect(protocol, endpointId, itemRefDecoded) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        viewModel.initialize(client, protocol, endpointId)

        // Set cached info immediately so the screen doesn't show a spinner
        if (initialInfo != null && vmEntryInfo == null) {
            viewModel.setEntryInfo(ArtUrlInjector.applyInfo(initialInfo, protocol))
            Log.d(LOG_TAG, "Using cached EntryInfo: type=${initialInfo.type}")
        }
        // Always fetch fresh data — replaces placeholder with real info
        viewModel.loadEntry()
    }

    // Load seasons when entry is a Series
    LaunchedEffect(vmEntryInfo?.type) {
        if (vmEntryInfo?.type == "Series") viewModel.loadSeasons()
    }

    // Load digipak children when entry is a Digipak
    LaunchedEffect(vmEntryInfo?.type) {
        if (vmEntryInfo?.type == "Digipak") viewModel.loadDigipakChildren()
    }

    // Load episodes when season changes
    LaunchedEffect(vmSeasons, vmSelectedSeasonIndex, vmEpisodePage) {
        if (vmSeasons.isNotEmpty()) viewModel.loadEpisodes()
    }

    val loader = imageLoader
    val entryInfo = vmEntryInfo
    Log.d(LOG_TAG, "render state: error=$vmError loading=$vmLoading entryInfo=${entryInfo?.type} loader=$loader")
    when {
        vmError != null -> Text("Error: ${vmError}")
        vmLoading || loader == null || entryInfo == null -> Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { CircularProgressIndicator() }
        else -> {
            val info = entryInfo
            val playFromStart = {
                sharedVm.cache(info)
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, info))
            }
            val resumePlay = {
                sharedVm.cache(info)
                nav.navigate(Routes.player(protocol, endpointId, itemRefDecoded, info, resumeMs))
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
            val selectSeason = { index: Int -> viewModel.selectSeason(index) }
            val selectEpisode = { episode: EntryInfo ->
                val startMs = episode.userData?.positionMs ?: 0L
                sharedVm.cache(episode)
                nav.navigate(Routes.player(protocol, endpointId, episode.id, episode, startMs))
            }
            val selectPage = { page: Int -> viewModel.selectEpisodePage(page) }
            val selectChild = { child: EntryInfo ->
                val startMs = child.userData?.positionMs ?: 0L
                sharedVm.cache(child)
                nav.navigate(Routes.player(protocol, endpointId, child.id, child, startMs))
            }
            val playSingleChild: () -> Unit = run {
                val child = vmSingleChild
                {
                    if (child != null) {
                        val startMs = child.userData?.positionMs ?: 0L
                        sharedVm.cache(child)
                        nav.navigate(Routes.player(protocol, endpointId, child.id, child, startMs))
                    }
                }
            }

            when (info.type) {
                "Movie", "Episode", "Video" -> MovieOverviewScreen(
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
                    seasons = vmSeasons,
                    selectedSeasonIndex = vmSelectedSeasonIndex,
                    episodes = vmEpisodes,
                    totalEpisodes = vmTotalEpisodes,
                    episodePage = vmEpisodePage,
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
                    children = vmDigipakChildren,
                    singleChild = vmSingleChild,
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
