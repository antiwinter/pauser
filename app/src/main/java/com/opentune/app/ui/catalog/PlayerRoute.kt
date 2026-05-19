package com.opentune.app.ui.catalog

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
import com.opentune.app.OpenTuneApplication
import com.opentune.player.OpenTunePlayer
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.EntryStateKey
import com.opentune.storage.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PLAYER_ROUTE_LOG = "OT_PlayerRoute"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerRoute(
    app: OpenTuneApplication,
    protocol: String,
    endpointId: String,
    itemRefDecoded: String,
    startMs: Long,
    onExit: () -> Unit,
) {
    val stateKey = remember(protocol, endpointId, itemRefDecoded) {
        EntryStateKey(protocol, endpointId, itemRefDecoded)
    }
    var spec by remember { mutableStateOf<PlaybackSpec?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var initialSubtitleTrackId by remember { mutableStateOf<String?>(null) }
    var initialAudioTrackId by remember { mutableStateOf<String?>(null) }
    var initialSubtitlePrefs by remember { mutableStateOf(SubtitlePrefs()) }

    LaunchedEffect(protocol, endpointId, itemRefDecoded, startMs) {
        spec = null
        error = null
        try {
            withContext(Dispatchers.IO) {
                val inst = app.endpointClientRegistry.getOrCreate(endpointId)
                    ?: throw IllegalStateException("No provider instance for $endpointId")
                val resolvedSpec = inst.getPlaybackSpec(itemRefDecoded, startMs)
                val savedState = app.storageBindings.entryStateStore.get(protocol, endpointId, itemRefDecoded)
                val subtitlePrefs = app.storageBindings.appConfigStore.loadSubtitlePrefs()
                initialSubtitleTrackId = savedState?.selectedSubtitleTrackId
                initialAudioTrackId = savedState?.selectedAudioTrackId
                initialSubtitlePrefs = subtitlePrefs
                Log.d(PLAYER_ROUTE_LOG, "PlayerRoute: key=$protocol/${endpointId}/${itemRefDecoded} savedState=$savedState subtitleTrackId=$initialSubtitleTrackId audioTrackId=$initialAudioTrackId subtitlePrefs=$subtitlePrefs")
                spec = resolvedSpec
            }
        } catch (e: Exception) {
            error = e.message ?: "Playback failed"
        }
    }

    when {
        error != null -> {
            Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                Text("Error: $error")
                Button(onClick = onExit) { Text("Back") }
            }
        }
        spec != null -> {
            PlayerShell {
                OpenTunePlayer(
                    spec = spec!!,
                    startMs = startMs,
                    entryStateStore = app.storageBindings.entryStateStore,
                    entryStateKey = stateKey,
                    onExit = onExit,
                    initialSubtitleTrackId = initialSubtitleTrackId,
                    initialAudioTrackId = initialAudioTrackId,
                    initialSubtitleOffsetFraction = initialSubtitlePrefs.offsetFraction,
                    initialSubtitleSizeScale = initialSubtitlePrefs.sizeScale,
                    appConfigStore = app.storageBindings.appConfigStore,
                )
            }
        }
        else -> {
            Text("Loading\u2026", modifier = Modifier.padding(48.dp))
        }
    }
}
