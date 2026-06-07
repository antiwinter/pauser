package com.opentune.content.ui.catalog

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EndpointClient
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
    // Player overlay state — shown when user presses Play
    var playerOverlayVisible by remember { mutableStateOf(false) }
    var overlayStartMs by remember { mutableLongStateOf(0L) }
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
    var initialEpisodeIndex by remember { mutableStateOf(0) }

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
            val (season, episode) = decodeSeriesProgress(rawPosition)
            if (season > 0) viewModel.selectSeason(maxOf(0, season - 1))
            if (episode > 0) initialEpisodeIndex = episode - 1
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

    // Pre-buffer Movie immediately after entry is loaded
    LaunchedEffect(vmEntryInfo?.id, vmEntryInfo?.type, playerController != null) {
        val info = vmEntryInfo ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        if (info.type == "Movie" || info.type == "Video") {
            Log.d(LOG_TAG, "pre-buffer Movie: ref=$itemRefDecoded startMs=$resumeMs")
            val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
            controller.setItem(itemRefDecoded, client, resumeMs)
        }
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

    // Pre-buffer Series episode when episodes list loads
    LaunchedEffect(vmEpisodes, playerController != null) {
        val episodes = vmEpisodes
        if (episodes.isEmpty()) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        // Find episode with resume position
        val currentEpisode = episodes.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
            ?: episodes.firstOrNull()
            ?: return@LaunchedEffect
        val startMs = currentEpisode.userData?.positionMs ?: 0L
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        Log.d(LOG_TAG, "pre-buffer Series episode: ref=${currentEpisode.id} startMs=$startMs")
        controller.setItem(currentEpisode.id, client, startMs)
    }

    // Pre-buffer Digipak child when children list loads
    LaunchedEffect(vmDigipakChildren, vmSingleChild, playerController != null) {
        val children = vmDigipakChildren
        val singleChild = vmSingleChild
        val controller = playerController ?: return@LaunchedEffect

        val (child, startMs) = when {
            singleChild != null -> singleChild to (singleChild.userData?.positionMs ?: 0L)
            children.isNotEmpty() -> {
                val c = children.firstOrNull { it.userData?.positionMs ?: 0L > 0L }
                    ?: children.first()
                c to (c.userData?.positionMs ?: 0L)
            }
            else -> return@LaunchedEffect
        }
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        Log.d(LOG_TAG, "pre-buffer Digipak child: ref=${child.id} startMs=$startMs")
        controller.setItem(child.id, client, startMs)
    }

    // Release player when leaving detail screen
    DisposableEffect(playerController) {
        onDispose {
            playerController?.release()
            Log.d(LOG_TAG, "detail disposed: player released")
        }
    }

    val loader = imageLoader
    val entryInfo = vmEntryInfo
    val ctrlExoPlayer = playerController?.exoPlayer
    val vmMediaCodecs by (playerController?.mediaCodecs
        ?: MutableStateFlow(emptyList<MediaCodecInfo>())).collectAsState()

    // Player overlay — full-screen when user presses Play
    if (playerOverlayVisible) {
        if (ctrlExoPlayer != null) {
            Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                PlayerSurface(
                    exoPlayer = ctrlExoPlayer,
                    startMs = overlayStartMs,
                    onBack = {
                        playerController.pause()
                        playerOverlayVisible = false
                        Log.d(LOG_TAG, "player overlay: back → pause & hide")
                    },
                )
            }
            return
        }
        // Loading state — show "Loading…" until ExoPlayer is ready
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            LoadingOverlay(onBack = {
                playerOverlayVisible = false
                Log.d(LOG_TAG, "player overlay: back during loading → hide")
            })
        }
        return
    }

    when {
        vmError != null -> Text("Error: ${vmError}")
        vmLoading || loader == null || entryInfo == null -> Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { CircularProgressIndicator() }
        else -> {
            val info = entryInfo

            // Helper: show overlay + set item in controller.
            // The controller handles same-item reuse, debounce, and prepare lifecycle.
            val showPlayer = { ref: String, start: Long, client: EndpointClient ->
                overlayStartMs = start
                playerOverlayVisible = true
                playerController?.setItem(ref, client, start)
            }

            val playFromStart = {
                overlayStartMs = 0L
                playerOverlayVisible = true
                playerController?.play()
                Unit
            }
            val resumePlay = {
                overlayStartMs = resumeMs
                playerOverlayVisible = true
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
            val selectSeason = { index: Int -> viewModel.selectSeason(index) }
            val selectEpisode = { episode: EntryInfo ->
                val startMs = episode.userData?.positionMs ?: 0L
                sharedVm.cache(episode)
                overlayStartMs = startMs
                playerOverlayVisible = true
                scope.launch {
                    val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                    if (client != null) {
                        playerController?.setItem(episode.id, client, startMs)
                    }
                }
                Unit
            }
            val selectPage = { page: Int -> viewModel.selectEpisodePage(page) }
            val selectChild = { child: EntryInfo ->
                val startMs = child.userData?.positionMs ?: 0L
                sharedVm.cache(child)
                overlayStartMs = startMs
                playerOverlayVisible = true
                scope.launch {
                    val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                    if (client != null) {
                        playerController?.setItem(child.id, client, startMs)
                    }
                }
                Unit
            }
            val playSingleChild: () -> Unit = run {
                val child = vmSingleChild
                {
                    if (child != null) {
                        val startMs = child.userData?.positionMs ?: 0L
                        sharedVm.cache(child)
                        overlayStartMs = startMs
                        playerOverlayVisible = true
                        scope.launch {
                            val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                            if (client != null) {
                                playerController?.setItem(child.id, client, startMs)
                            }
                        }
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
                    selectedSeasonIndex = vmSelectedSeasonIndex,
                    episodes = vmEpisodes,
                    totalEpisodes = vmTotalEpisodes,
                    episodePage = vmEpisodePage,
                    imageLoader = loader,
                    mediaCodecs = vmMediaCodecs,
                    initialEpisodeIndex = initialEpisodeIndex,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingOverlay(onBack: () -> Unit) {
    Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Loading…")
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
}
