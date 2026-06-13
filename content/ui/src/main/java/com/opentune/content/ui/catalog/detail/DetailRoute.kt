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
import com.opentune.content.ui.catalog.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    endpointId: String,
    itemRef: String,
    initialInfo: EntryInfo? = null,
    sharedVm: NavSharedViewModel,
    viewModel: DetailViewModel,
    playerController: PlayerController? = null,
) {
    var resumeMs by remember { mutableStateOf(0L) }
    val stateKey = remember(endpointId, itemRef) {
        EntryStateKey(endpointId, itemRef)
    }
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    val vmEntryInfo by viewModel.entryInfo.collectAsState()

    var imageLoader by remember { mutableStateOf<coil3.ImageLoader?>(null) }

    // Resolve client, load stored position (resumeMs — progress sync deferred).
    LaunchedEffect(endpointId) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
            ?: throw IllegalStateException("No provider instance for $endpointId")
        imageLoader = client.imageLoader
        val entryState = withContext(Dispatchers.IO) {
            StorageBindingsHolder.get().entryStateStore.get(stateKey)
        }
        resumeMs = entryState?.positionMs ?: 0L
    }

    // Initialize ViewModel and load entry info.
    LaunchedEffect(endpointId) {
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        viewModel.initialize(client, endpointId)
        if (initialInfo != null && vmEntryInfo == null) {
            viewModel.setEntryInfo(ArtUrlInjector.applyInfo(initialInfo, client.protocol))
            Log.d(LOG_TAG, "Using cached EntryInfo: type=${initialInfo.type}")
        }
    }

    // Set client on playerController once per endpoint.
    LaunchedEffect(endpointId) {
        val controller = playerController ?: return@LaunchedEffect
        val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId) ?: return@LaunchedEffect
        controller.setClient(client)
    }

    // Stop player when leaving detail entirely.
    DisposableEffect(playerController) {
        onDispose { playerController?.reset() }
    }

    // Back from detail: navigate away (player stops via DisposableEffect above).
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
                when (info.type) {
                    "Movie" -> MovieDetailRoute(
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        viewModel = viewModel,
                    )
                    "Series" -> SeriesDetailRoute(
                        stateKey = stateKey,
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        imageLoader = loader,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        viewModel = viewModel,
                        sharedVm = sharedVm,
                    )
                    "Digipak" -> DigipakDetailRoute(
                        entryInfo = info,
                        titleLang = titleLang,
                        resumeMs = resumeMs,
                        imageLoader = loader,
                        mediaCodecs = vmMediaCodecs,
                        playerController = playerController,
                        viewModel = viewModel,
                        sharedVm = sharedVm,
                    )
                    else -> Text("Unsupported type: ${info.type}")
                }
            }
        }
    }
}
