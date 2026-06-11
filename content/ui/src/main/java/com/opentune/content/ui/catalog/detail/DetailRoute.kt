package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.player.MediaCodecInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import com.opentune.content.ui.catalog.ArtUrlInjector
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.CatalogNav
import com.opentune.content.ui.catalog.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

internal data class PlaybackSelection(
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
        EntryStateKey(endpointId, itemRefDecoded)
    }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    val vmEntryInfo by viewModel.entryInfo.collectAsState()

    var imageLoader by remember { mutableStateOf<coil3.ImageLoader?>(null) }
    var isFavorite by remember { mutableStateOf(false) }

    // Resolve client, load stored state (position, favorite).
    LaunchedEffect(protocol, endpointId) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
            ?: throw IllegalStateException("No provider instance for $endpointId")
        imageLoader = client.imageLoader
        val entryState = withContext(Dispatchers.IO) {
            StorageBindingsHolder.get().entryStateStore.get(stateKey)
        }
        isFavorite = entryState?.isFavorite ?: false
        resumeMs = entryState?.positionMs ?: 0L
    }

    // Initialize ViewModel and load entry info.
    LaunchedEffect(protocol, endpointId, itemRefDecoded) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        viewModel.initialize(client, protocol, endpointId)
        if (initialInfo != null && vmEntryInfo == null) {
            viewModel.setEntryInfo(ArtUrlInjector.applyInfo(initialInfo, protocol))
            Log.d(LOG_TAG, "Using cached EntryInfo: type=${initialInfo.type}")
        }
    }

    // Stop player when leaving detail entirely.
    DisposableEffect(playerController) {
        onDispose { playerController?.reset() }
    }

    // Back from detail: navigate away (player stops via DisposableEffect above).
    // When surface is visible, TvPlayerSurface's BackHandler takes priority.
    BackHandler {
        nav.popBackStack()
    }

    val loader = imageLoader
    val entryInfo = vmEntryInfo
    val vmMediaCodecs by (playerController?.mediaCodecs
        ?: MutableStateFlow(emptyList<MediaCodecInfo>())).collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loader == null || entryInfo == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                val info = entryInfo
                val toggleFav: () -> Unit = {
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

                when (info.type) {
                    "Movie" -> MovieDetailRoute(
                        protocol = protocol,
                        endpointId = endpointId,
                        itemRefDecoded = itemRefDecoded,
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        isFavorite = isFavorite,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        onToggleFavorite = toggleFav,
                    )
                    "Series" -> SeriesDetailRoute(
                        protocol = protocol,
                        endpointId = endpointId,
                        itemRefDecoded = itemRefDecoded,
                        stateKey = stateKey,
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        isFavorite = isFavorite,
                        imageLoader = loader,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        viewModel = viewModel,
                        sharedVm = sharedVm,
                        onToggleFavorite = toggleFav,
                    )
                    "Digipak" -> DigipakDetailRoute(
                        protocol = protocol,
                        endpointId = endpointId,
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        isFavorite = isFavorite,
                        imageLoader = loader,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        viewModel = viewModel,
                        sharedVm = sharedVm,
                        onToggleFavorite = toggleFav,
                    )
                    else -> Text("Unsupported type: ${info.type}")
                }
            }
        }
    }
}
