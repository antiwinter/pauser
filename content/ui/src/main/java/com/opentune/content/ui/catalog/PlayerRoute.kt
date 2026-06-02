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
import androidx.compose.runtime.CompositionLocalProvider
import com.opentune.player.LocalPlaybackStorageContext
import com.opentune.player.OpenTunePlayer
import com.opentune.player.PlaybackStorageContext
import com.opentune.content.contract.EntryInfo
import com.opentune.player.PlaybackSpec
import com.opentune.storage.EntryStateKey
import com.opentune.storage.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PLAYER_ROUTE_LOG = "OT_PlayerRoute"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerRoute(
    protocol: String,
    endpointId: String,
    itemRefDecoded: String,
    startMs: Long,
    entryInfo: EntryInfo? = null,
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
                val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                    ?: throw IllegalStateException("No provider instance for $endpointId")
                val resolvedSpec = client.getPlaybackSpec(itemRefDecoded, startMs)
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
                Log.d(PLAYER_ROUTE_LOG, "PlayerRoute: key=$protocol/${endpointId}/${itemRefDecoded} subtitleTrackId=$initialSubtitleTrackId audioTrackId=$initialAudioTrackId")
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
            CompositionLocalProvider(
                LocalPlaybackStorageContext provides PlaybackStorageContext(
                    entryStateStore = StorageBindingsHolder.get().entryStateStore,
                    entryStateKey = stateKey,
                    parentStateKey = parentKey,
                    seriesStateKey = seriesKey,
                    seriesSeasonNumber = entryInfo?.seasonNumber,
                    seriesEpisodeNumber = entryInfo?.indexNumber,
                    appConfigStore = StorageBindingsHolder.get().appConfigStore,
                )
            ) {
                PlayerShell {
                    OpenTunePlayer(
                        spec = spec!!,
                        startMs = startMs,
                        onExit = onExit,
                        initialSubtitleTrackId = initialSubtitleTrackId,
                        initialAudioTrackId = initialAudioTrackId,
                        initialSubtitleOffsetFraction = initialSubtitlePrefs.offsetFraction,
                        initialSubtitleSizeScale = initialSubtitlePrefs.sizeScale,
                    )
                }
            }
        }
        else -> {
            Text("Loading\u2026", modifier = Modifier.padding(48.dp))
        }
    }
}
