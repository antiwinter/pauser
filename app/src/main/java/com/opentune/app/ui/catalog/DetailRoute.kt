package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaListItem
import com.opentune.storage.MediaStateKey
import com.opentune.storage.TitleLang
import com.opentune.storage.get
import com.opentune.storage.upsertFavorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    itemRefEncoded: String,
) {
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(providerType, sourceId, itemRefDecoded) {
        MediaStateKey(providerType, sourceId, itemRefDecoded)
    }
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    var detail by remember { mutableStateOf<MediaDetailModel?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var resumeMs by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Series/season UI state
    var seasons by remember { mutableStateOf<List<MediaListItem>?>(null) }
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<MediaListItem>>(emptyList()) }
    var totalEpisodes by remember { mutableIntStateOf(0) }
    var episodePage by remember { mutableIntStateOf(0) }

    LaunchedEffect(providerType, sourceId, itemRefDecoded) {
        loading = true
        error = null
        seasons = null
        selectedSeasonIndex = 0
        episodes = emptyList()
        episodePage = 0
        try {
            val inst = app.instanceRegistry.getOrCreate(sourceId)
                ?: throw IllegalStateException("No provider instance for $sourceId")
            val mediaState = withContext(Dispatchers.IO) {
                app.storageBindings.mediaStateStore.get(stateKey)
            }
            isFavorite = mediaState?.isFavorite ?: false
            resumeMs = mediaState?.positionMs ?: 0L
            val d = withContext(Dispatchers.IO) { inst.loadDetail(itemRefDecoded) }
            detail = d
            if (!d.canPlay) {
                val result = withContext(Dispatchers.IO) {
                    inst.loadBrowsePage(itemRefDecoded, 0, 500)
                }
                seasons = result.items
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "detail load", e)
            error = e.message
        } finally {
            loading = false
        }
    }

    // Load episodes when season selection changes
    LaunchedEffect(seasons, selectedSeasonIndex, episodePage) {
        val seasonList = seasons ?: return@LaunchedEffect
        val season = seasonList.getOrNull(selectedSeasonIndex) ?: return@LaunchedEffect
        try {
            val inst = app.instanceRegistry.getOrCreate(sourceId) ?: return@LaunchedEffect
            val result = withContext(Dispatchers.IO) {
                inst.loadBrowsePage(season.id, episodePage * 50, 50)
            }
            episodes = result.items.sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            totalEpisodes = result.totalCount
        } catch (e: Exception) {
            Log.e(LOG_TAG, "episodes load", e)
        }
    }

    when {
        error != null -> Text("Error: $error")
        else -> DetailScreen(
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
            onBack = { nav.popBackStack() },
            onPlayFromStart = {
                nav.navigate(Routes.player(providerType, sourceId, itemRefDecoded, 0L))
            },
            onResume = {
                nav.navigate(Routes.player(providerType, sourceId, itemRefDecoded, resumeMs))
            },
            onToggleFavorite = {
                scope.launch {
                    val newVal = !isFavorite
                    isFavorite = newVal
                    try {
                        withContext(Dispatchers.IO) {
                            app.storageBindings.mediaStateStore.upsertFavorite(stateKey, newVal)
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
                nav.navigate(Routes.player(providerType, sourceId, episode.id, startMs))
            },
            onSelectPage = { page -> episodePage = page },
        )
    }
}
