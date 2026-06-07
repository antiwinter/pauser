package com.opentune.content.ui.catalog
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.storage.StorageBindingsHolder

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.content.contract.EntryInfo
import com.opentune.storage.EntryStateKey
import com.opentune.storage.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PLAYER_ROUTE_LOG = "OT_PlayerRoute"

/**
 * Thin PlayerRoute — resolves PlaybackSpec, hands off to PlayerController.
 * The PlayerController owns the ExoPlayer; this route just renders the surface.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerRoute(
    protocol: String,
    endpointId: String,
    itemRefDecoded: String,
    startMs: Long,
    entryInfo: EntryInfo? = null,
    playerController: PlayerController,
    onExit: () -> Unit,
) {
    val stateKey = remember(protocol, endpointId, itemRefDecoded) {
        EntryStateKey(protocol, endpointId, itemRefDecoded)
    }
    val parentKey = remember(protocol, endpointId, entryInfo) {
        entryInfo?.parentId?.let { EntryStateKey(protocol, endpointId, it) }
    }
    val seriesKey = remember(protocol, endpointId, entryInfo) {
        entryInfo?.seriesId?.let { EntryStateKey(protocol, endpointId, it) }
    }

    var error by remember { mutableStateOf<String?>(null) }
    var initialSubtitleTrackId by remember { mutableStateOf<String?>(null) }
    var initialAudioTrackId by remember { mutableStateOf<String?>(null) }
    var initialSubtitlePrefs by remember { mutableStateOf(SubtitlePrefs()) }

    // Resolve player and prepare
    LaunchedEffect(protocol, endpointId, itemRefDecoded, startMs) {
        error = null
        try {
            val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                ?: throw IllegalStateException("No provider instance for $endpointId")

            // Look up saved subtitle/audio track prefs from local storage
            val store = StorageBindingsHolder.get().entryStateStore
            val episodeState = store.get(protocol, endpointId, itemRefDecoded)
            val parentState = entryInfo?.parentId?.let { store.get(protocol, endpointId, it) }
            val seriesState = entryInfo?.seriesId?.let { store.get(protocol, endpointId, it) }
            val subtitlePrefs = StorageBindingsHolder.get().appConfigStore.loadSubtitlePrefs()
            initialSubtitleTrackId = episodeState?.selectedSubtitleTrackId
                ?: parentState?.selectedSubtitleTrackId
                ?: seriesState?.selectedSubtitleTrackId
            initialAudioTrackId = episodeState?.selectedAudioTrackId
                ?: parentState?.selectedAudioTrackId
                ?: seriesState?.selectedAudioTrackId
            initialSubtitlePrefs = subtitlePrefs

            Log.d(PLAYER_ROUTE_LOG, "PlayerRoute: key=$protocol/$endpointId/$itemRefDecoded subtitle=$initialSubtitleTrackId")

            // Hand off to PlayerController (it resolves PlaybackSpec internally)
            // TODO: pass subtitle/audio track prefs to PlayerController
            playerController.prepare(itemRefDecoded, client, startMs)
        } catch (e: Exception) {
            Log.e(PLAYER_ROUTE_LOG, "PlayerRoute error", e)
            error = e.message ?: "Playback failed"
        }
    }

    val exo = playerController.exoPlayer
    Log.d(PLAYER_ROUTE_LOG, "render: exoPlayer=${if (exo != null) "non-null" else "null"} error=$error")
    when {
        error != null -> {
            Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                Text("Error: $error")
                Button(onClick = onExit) { Text("Back") }
            }
        }
        exo != null -> {
            PlayerSurface(
                exoPlayer = exo,
                startMs = startMs,
                onBack = {
                    playerController.release()
                    onExit()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> {
            Text("Loading… (PlayerRoute, no ExoPlayer)", modifier = Modifier.padding(48.dp))
        }
    }
}
