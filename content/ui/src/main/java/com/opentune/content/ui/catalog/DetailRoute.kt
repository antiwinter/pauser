package com.opentune.content.ui.catalog

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.storage.decodeSeriesProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

private data class PlaybackSelection(
    val itemRef: String,
    val startMs: Long = 0L,
    val seriesStateKey: EntryStateKey? = null,
)

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
    playerController: PlayerController? = null,
) {
    var resumeMs by remember { mutableStateOf(0L) }
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(protocol, endpointId, itemRefDecoded) {
        EntryStateKey(protocol, endpointId, itemRefDecoded)
    }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    val vmEntryInfo by viewModel.entryInfo.collectAsState()
    val vmSeasons by viewModel.seasons.collectAsState()
    val vmSelectedSeasonId by viewModel.selectedSeasonId.collectAsState()
    val vmEpisodes by viewModel.episodes.collectAsState()
    val vmTotalEpisodes by viewModel.totalEpisodes.collectAsState()
    val vmEpisodePage by viewModel.episodePage.collectAsState()
    val vmSelectedEpisodeId by viewModel.selectedEpisodeId.collectAsState()
    val vmDigipakChildren by viewModel.digipakChildren.collectAsState()
    val vmSingleChild by viewModel.singleChild.collectAsState()
    val vmLoading by viewModel.loading.collectAsState()
    val vmError by viewModel.error.collectAsState()

    var imageLoader by remember { mutableStateOf<coil3.ImageLoader?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    // Set to true when requestNextVideo crosses a season boundary; consumed on next episode load.
    var pendingAutoPlay by remember { mutableStateOf(false) }
    // Season/episode numbers decoded from storage, resolved to ids once the lists load.
    var pendingSeasonNumber by remember { mutableStateOf(0) }
    var pendingEpisodeNumber by remember { mutableStateOf(0) }
    var playbackSelection by remember { mutableStateOf<PlaybackSelection?>(null) }

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
        resumeMs = if (info?.type == "Series" && entryState != null) {
            val (season, episode) = decodeSeriesProgress(rawPosition)
            if (season > 0) pendingSeasonNumber = season
            if (episode > 0) pendingEpisodeNumber = episode
            rawPosition
        } else {
            rawPosition
        }
    }

    LaunchedEffect(protocol, endpointId, itemRefDecoded) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        viewModel.initialize(client, protocol, endpointId)
        if (initialInfo != null && vmEntryInfo == null) {
            viewModel.setEntryInfo(ArtUrlInjector.applyInfo(initialInfo, protocol))
            Log.d(LOG_TAG, "Using cached EntryInfo: type=${initialInfo.type}")
        }
        viewModel.loadEntry()
    }

    // Movie/Video: select on entry info loaded.
    LaunchedEffect(vmEntryInfo?.id, vmEntryInfo?.type) {
        val info = vmEntryInfo ?: return@LaunchedEffect
        if (info.type == "Movie" || info.type == "Video") {
            playbackSelection = PlaybackSelection(itemRefDecoded, resumeMs)
        }
    }

    LaunchedEffect(vmEntryInfo?.type) {
        if (vmEntryInfo?.type == "Series") viewModel.loadSeasons()
    }

    LaunchedEffect(vmEntryInfo?.type) {
        if (vmEntryInfo?.type == "Digipak") viewModel.loadDigipakChildren()
    }

    LaunchedEffect(vmSeasons, vmSelectedSeasonId, vmEpisodePage) {
        // Resolve pending season from storage by indexNumber, then load episodes.
        if (vmSeasons.isNotEmpty() && pendingSeasonNumber > 0) {
            val season = vmSeasons.firstOrNull { it.indexNumber == pendingSeasonNumber }
                ?: vmSeasons.first()
            pendingSeasonNumber = 0
            viewModel.selectSeason(season.id)
            return@LaunchedEffect  // selectSeason triggers this effect again
        }
        if (vmSeasons.isNotEmpty() && viewModel.selectedSeasonId.value == null && pendingSeasonNumber == 0) {
            viewModel.selectSeason(vmSeasons.first().id)
            return@LaunchedEffect
        }
        if (vmSeasons.isNotEmpty()) viewModel.loadEpisodes()
    }

    // When Series episodes load: resolve pending episode number, handle initial selection and cross-season auto-play.
    LaunchedEffect(vmEpisodes) {
        if (vmEpisodes.isEmpty()) return@LaunchedEffect
        if (pendingAutoPlay) {
            pendingAutoPlay = false
            val episode = vmEpisodes.first()
            Log.d(LOG_TAG, "auto-advance season: episode=${episode.id}")
            viewModel.setSelectedEpisodeId(episode.id)
            playbackSelection = PlaybackSelection(episode.id, 0L, stateKey)
            withContext(Dispatchers.IO) {
                StorageBindingsHolder.get().entryStateStore.upsertSeriesProgress(
                    stateKey, episode.seasonNumber ?: 0, episode.indexNumber ?: 0
                )
            }
            playerController?.play()
            return@LaunchedEffect
        }
        if (pendingEpisodeNumber > 0) {
            val episode = vmEpisodes.firstOrNull { it.indexNumber == pendingEpisodeNumber }
                ?: vmEpisodes.first()
            pendingEpisodeNumber = 0
            viewModel.setSelectedEpisodeId(episode.id)
            playbackSelection = PlaybackSelection(episode.id, episode.userData?.positionMs ?: 0L, stateKey)
            return@LaunchedEffect
        }
        if (viewModel.selectedEpisodeId.value == null) {
            val episode = vmEpisodes.first()
            viewModel.setSelectedEpisodeId(episode.id)
            playbackSelection = PlaybackSelection(episode.id, episode.userData?.positionMs ?: 0L, stateKey)
        }
    }

    // Digipak child: select on children loaded.
    LaunchedEffect(vmDigipakChildren, vmSingleChild) {
        val child = vmSingleChild
            ?: vmDigipakChildren.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: vmDigipakChildren.firstOrNull()
            ?: return@LaunchedEffect
        playbackSelection = PlaybackSelection(child.id, child.userData?.positionMs ?: 0L)
    }

    // Unified prepare: the single place prepare() is called, driven by selection state.
    LaunchedEffect(playbackSelection) {
        val sel = playbackSelection ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        Log.d(LOG_TAG, "prepare: ref=${sel.itemRef} startMs=${sel.startMs}")
        controller.prepare(protocol, endpointId, sel.itemRef, client, sel.startMs, seriesStateKey = sel.seriesStateKey)
    }

    // Register requestNextVideo callback for Series, and stop player on dispose.
    DisposableEffect(playerController, vmEntryInfo?.type) {
        if (playerController != null && vmEntryInfo?.type == "Series") {
            playerController.setNextVideoCallback {
                val episodes = viewModel.episodes.value
                val currentId = viewModel.selectedEpisodeId.value
                val currentIdx = episodes.indexOfFirst { it.id == currentId }
                val nextEpisode = episodes.getOrNull(currentIdx + 1)
                if (nextEpisode != null) {
                    Log.d(LOG_TAG, "requestNextVideo: episode=${nextEpisode.id}")
                    viewModel.setSelectedEpisodeId(nextEpisode.id)  // → selection effect → playbackSelection → prepare
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StorageBindingsHolder.get().entryStateStore.upsertSeriesProgress(
                                stateKey, nextEpisode.seasonNumber ?: 0, nextEpisode.indexNumber ?: 0
                            )
                        }
                        playerController.play()
                    }
                } else {
                    val seasons = viewModel.seasons.value
                    val currentIdx = seasons.indexOfFirst { it.id == viewModel.selectedSeasonId.value }
                    val nextSeason = seasons.getOrNull(currentIdx + 1)
                    if (nextSeason != null) {
                        Log.d(LOG_TAG, "requestNextVideo: advancing to season ${nextSeason.id}")
                        pendingAutoPlay = true
                        viewModel.selectSeason(nextSeason.id)
                    }
                }
            }
        }
        onDispose {
            playerController?.setNextVideoCallback(null)
            playerController?.stop()
            Log.d(LOG_TAG, "detail disposed: player stopped, nextVideo callback cleared")
        }
    }

    // Back from detail: stop pre-buffer and navigate away.
    // When surface is visible, TvPlayerSurface's BackHandler takes priority.
    BackHandler {
        playerController?.stop()
        nav.popBackStack()
    }

    val loader = imageLoader
    val entryInfo = vmEntryInfo
    val vmMediaCodecs by (playerController?.mediaCodecs
        ?: MutableStateFlow(emptyList<MediaCodecInfo>())).collectAsState()

    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when {
            vmError != null -> Text("Error: ${vmError}")
            vmLoading || loader == null || entryInfo == null -> Box(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                val info = entryInfo

                val playFromStart = {
                    playbackSelection = playbackSelection?.copy(startMs = 0L)
                        ?: PlaybackSelection(itemRefDecoded, 0L)
                    playerController?.play()
                    Unit
                }
                val resumePlay = {
                    playerController?.play()
                    Unit
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
                val selectSeason = { id: String -> viewModel.selectSeason(id) }
                // Focus on an episode (D-pad) → update ViewModel selection + set playbackSelection → prepare fires.
                val focusEpisode = { episode: EntryInfo ->
                    sharedVm.cache(episode)
                    viewModel.setSelectedEpisodeId(episode.id)
                    playbackSelection = PlaybackSelection(episode.id, episode.userData?.positionMs ?: 0L, stateKey)
                }
                // Click on an episode → write series progress and play (already prepared by focusEpisode).
                val selectEpisode = { episode: EntryInfo ->
                    sharedVm.cache(episode)
                    viewModel.setSelectedEpisodeId(episode.id)
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StorageBindingsHolder.get().entryStateStore.upsertSeriesProgress(
                                stateKey, episode.seasonNumber ?: 0, episode.indexNumber ?: 0
                            )
                        }
                        playerController?.play()
                    }
                    Unit
                }
                val selectPage = { page: Int -> viewModel.selectEpisodePage(page) }
                val selectChild = { child: EntryInfo ->
                    val startMs = child.userData?.positionMs ?: 0L
                    sharedVm.cache(child)
                    playbackSelection = PlaybackSelection(child.id, startMs)
                    playerController?.play()
                    Unit
                }
                val playSingleChild: () -> Unit = run {
                    val child = vmSingleChild
                    {
                        if (child != null) {
                            val startMs = child.userData?.positionMs ?: 0L
                            sharedVm.cache(child)
                            playbackSelection = PlaybackSelection(child.id, startMs)
                            playerController?.play()
                        }
                    }
                }

                when (info.type) {
                    "Movie", "Episode", "Video" -> MovieOverviewScreen(
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        isFavorite = isFavorite,
                        mediaCodecs = vmMediaCodecs,
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
                        selectedSeasonId = vmSelectedSeasonId,
                        episodes = vmEpisodes,
                        totalEpisodes = vmTotalEpisodes,
                        episodePage = vmEpisodePage,
                        imageLoader = loader,
                        mediaCodecs = vmMediaCodecs,
                        initialEpisodeIndex = vmEpisodes.indexOfFirst { it.id == vmSelectedEpisodeId }.coerceAtLeast(0),
                        onFocusEpisode = focusEpisode,
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
                        mediaCodecs = vmMediaCodecs,
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
}
