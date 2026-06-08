package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.storage.decodeSeriesProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_SeriesDetail"

@Composable
fun SeriesDetailRoute(
    protocol: String,
    endpointId: String,
    itemRefDecoded: String,
    stateKey: EntryStateKey,
    entryInfo: EntryInfo,
    titleLang: TitleLang,
    resumeMs: Long,
    isFavorite: Boolean,
    imageLoader: ImageLoader,
    mediaCodecs: List<MediaCodecInfo>,
    playerController: PlayerController?,
    viewModel: DetailViewModel,
    sharedVm: NavSharedViewModel,
    onToggleFavorite: () -> Unit,
) {
    var pendingSeasonNumber by remember { mutableStateOf(0) }
    var pendingEpisodeNumber by remember { mutableStateOf(0) }
    var pendingAutoPlay by remember { mutableStateOf(false) }
    var playbackSelection by remember { mutableStateOf<PlaybackSelection?>(null) }
    val scope = rememberCoroutineScope()

    val vmSeasons by viewModel.seasons.collectAsState()
    val vmSelectedSeasonId by viewModel.selectedSeasonId.collectAsState()
    val vmEpisodes by viewModel.episodes.collectAsState()
    val vmTotalEpisodes by viewModel.totalEpisodes.collectAsState()
    val vmEpisodePage by viewModel.episodePage.collectAsState()
    val vmSelectedEpisodeId by viewModel.selectedEpisodeId.collectAsState()

    // Decode stored series position and kick off season loading.
    LaunchedEffect(entryInfo.id) {
        val (season, episode) = decodeSeriesProgress(resumeMs)
        if (season > 0) pendingSeasonNumber = season
        if (episode > 0) pendingEpisodeNumber = episode
        viewModel.loadSeasons()
    }

    // Resolve season selection and load episodes.
    LaunchedEffect(vmSeasons, vmSelectedSeasonId, vmEpisodePage) {
        if (vmSeasons.isNotEmpty() && pendingSeasonNumber > 0) {
            val season = vmSeasons.firstOrNull { it.indexNumber == pendingSeasonNumber }
                ?: vmSeasons.first()
            pendingSeasonNumber = 0
            viewModel.selectSeason(season.id)
            return@LaunchedEffect
        }
        if (vmSeasons.isNotEmpty() && viewModel.selectedSeasonId.value == null && pendingSeasonNumber == 0) {
            viewModel.selectSeason(vmSeasons.first().id)
            return@LaunchedEffect
        }
        if (vmSeasons.isNotEmpty()) viewModel.loadEpisodes()
    }

    // Resolve episode selection; handle initial selection and cross-season auto-play.
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

    // Unified prepare.
    LaunchedEffect(playbackSelection) {
        val sel = playbackSelection ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        Log.d(LOG_TAG, "prepare: ref=${sel.itemRef} startMs=${sel.startMs}")
        controller.prepare(protocol, endpointId, sel.itemRef, client, sel.startMs, seriesStateKey = sel.seriesStateKey)
    }

    // Register requestNextVideo callback; stop player on dispose.
    DisposableEffect(playerController) {
        if (playerController != null) {
            playerController.setNextVideoCallback {
                val episodes = viewModel.episodes.value
                val currentId = viewModel.selectedEpisodeId.value
                val currentIdx = episodes.indexOfFirst { it.id == currentId }
                val nextEpisode = episodes.getOrNull(currentIdx + 1)
                if (nextEpisode != null) {
                    Log.d(LOG_TAG, "requestNextVideo: episode=${nextEpisode.id}")
                    viewModel.setSelectedEpisodeId(nextEpisode.id)
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
                    val currentSeasonIdx = seasons.indexOfFirst { it.id == viewModel.selectedSeasonId.value }
                    val nextSeason = seasons.getOrNull(currentSeasonIdx + 1)
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
            Log.d(LOG_TAG, "SeriesDetailRoute disposed: player stopped, nextVideo callback cleared")
        }
    }

    val playFromStart = {
        playbackSelection = playbackSelection?.copy(startMs = 0L)
            ?: PlaybackSelection(itemRefDecoded, 0L)
        playerController?.play()
        Unit
    }
    val resumePlay = { playerController?.play(); Unit }
    val selectSeason = { id: String -> viewModel.selectSeason(id) }
    val focusEpisode = { episode: EntryInfo ->
        sharedVm.cache(episode)
        viewModel.setSelectedEpisodeId(episode.id)
        playbackSelection = PlaybackSelection(episode.id, episode.userData?.positionMs ?: 0L, stateKey)
    }
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

    SeriesOverviewScreen(
        entryInfo = entryInfo,
        titleLang = titleLang,
        resumeMs = resumeMs,
        isFavorite = isFavorite,
        seasons = vmSeasons,
        selectedSeasonId = vmSelectedSeasonId,
        episodes = vmEpisodes,
        totalEpisodes = vmTotalEpisodes,
        episodePage = vmEpisodePage,
        imageLoader = imageLoader,
        mediaCodecs = mediaCodecs,
        initialEpisodeIndex = vmEpisodes.indexOfFirst { it.id == vmSelectedEpisodeId }.coerceAtLeast(0),
        onFocusEpisode = focusEpisode,
        onResume = resumePlay,
        onPlayFromStart = playFromStart,
        onToggleFavorite = onToggleFavorite,
        onSelectSeason = selectSeason,
        onSelectEpisode = selectEpisode,
        onSelectPage = selectPage,
    )
}
