package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.ImageLoader
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
    var pendingAutoPlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val vmSeasons by viewModel.seasons.collectAsState()
    val vmseasonIndex by viewModel.seasonIndex.collectAsState()
    val vmEpisodes by viewModel.episodes.collectAsState()
    val vmTotalEpisodes by viewModel.totalEpisodes.collectAsState()
    val vmpageIndex by viewModel.pageIndex.collectAsState()
    val vmsubEntryRef by viewModel.subEntryRef.collectAsState()

    // Set series context for playback state persistence.
    LaunchedEffect(stateKey) {
        playerController?.setContext(seriesStateKey = stateKey)
    }

    // Decode stored series position and kick off season loading.
    LaunchedEffect(entryInfo.ref) {
        val (season, episode) = decodeSeriesProgress(resumeMs)
        viewModel.loadEntries()
        viewModel.loadEntries(2, season, episode)
    }

    // Resolve episode focus target after episodes load.
    LaunchedEffect(vmEpisodes) {
        if (vmEpisodes.isEmpty()) return@LaunchedEffect
        
        playerController?.prepare(episode, 0L)  // auto-advance: start fresh
        if (pendingAutoPlay) {
            playerController?.play()
            pendingAutoPlay = false
        }
    }

    // Register requestNextVideo callback; cleared automatically by controller.stop().
    LaunchedEffect(playerController) {
        playerController?.setNextVideoCallback {
          viewModel.getNextEpisode()?.let { next ->
              Log.d(LOG_TAG, "Auto-advancing to next episode: S${next.seasonNumber}E${next.indexNumber} ${next.title}")
              pendingAutoPlay = true
          }
        }
    }

    val setSeason = { id: String -> viewModel.setSeason(id) }
    val focusEpisode = { episode: EntryInfo ->
        Log.d(LOG_TAG, "focusEpisode: ref=${episode.ref} title=${episode.title}")
        sharedVm.cache(episode)
        viewModel.setSubEntryRef(episode.ref)
        playerController?.prepare(episode)
        Unit
    }
    val selectEpisode = { episode: EntryInfo ->
        Log.d(LOG_TAG, "selectEpisode: ref=${episode.ref} title=${episode.title}")
        sharedVm.cache(episode)
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
    val selectPage = { page: Int -> viewModel.selectpageIndex(page) }

    SeriesOverviewScreen(
        entryInfo = entryInfo,
        titleLang = titleLang,
        isFavorite = isFavorite,
        seasons = vmSeasons,
        seasonIndex = vmseasonIndex,
        episodes = vmEpisodes,
        totalEpisodes = vmTotalEpisodes,
        pageIndex = vmpageIndex,
        imageLoader = imageLoader,
        mediaCodecs = mediaCodecs,
        initialFocusRef = vmsubEntryRef,
        onFocusEpisode = focusEpisode,
        onToggleFavorite = onToggleFavorite,
        onSelectSeason = setSeason,
        onSelectEpisode = selectEpisode,
        onSelectPage = selectPage,
    )
}
