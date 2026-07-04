package com.insomnia.content.ui.catalog.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.content.ui.catalog.player.PlayerStopEffect

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    endpointId: String,
    itemRef: String,
    initialInfo: EntryInfo? = null,
    viewModel: DetailViewModel,
    playerController: PlayerController? = null,
) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val client by viewModel.client.collectAsState()

    LaunchedEffect(endpointId, initialInfo) {
        viewModel.initialize(endpointId, initialInfo)
        // NOTE: initialize should load all direct sub-entries into subEntries. 
        // e.g. seasons, or digipak children.
    }

    LaunchedEffect(client) {
        client?.let { playerController?.setClient(it) }
    }

    PlayerStopEffect(playerController) {
        if (client != null) {
            viewModel.refresh(DetailRefreshScope.Lists)
        }
    }

    DisposableEffect(playerController) {
        onDispose { playerController?.reset() }
    }

    BackHandler {
        nav.popBackStack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            client == null || entryInfo == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> when (entryInfo!!.type) {
                "Movie" -> MovieDetailScreen(
                    playerController = playerController,
                    viewModel = viewModel,
                )
                "Series" -> SeriesDetailScreen(
                    playerController = playerController,
                    viewModel = viewModel,
                )
                "Digipak" -> DigipakDetailScreen(
                    playerController = playerController,
                    viewModel = viewModel,
                )
                else -> Text("Unsupported type: ${entryInfo!!.type}")
            }
        }
    }
}
