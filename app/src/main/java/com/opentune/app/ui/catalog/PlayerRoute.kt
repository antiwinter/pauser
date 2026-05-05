package com.opentune.app.ui.catalog

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
import com.opentune.player.OpenTunePlayerScreen
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.MediaStateKey
import com.opentune.storage.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerRoute(
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    itemRefDecoded: String,
    startMs: Long,
    onExit: () -> Unit,
) {
    val stateKey = remember(providerType, sourceId, itemRefDecoded) {
        MediaStateKey(providerType, sourceId, itemRefDecoded)
    }
    var spec by remember { mutableStateOf<PlaybackSpec?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providerType, sourceId, itemRefDecoded, startMs) {
        spec = null
        error = null
        try {
            spec = withContext(Dispatchers.IO) {
                val resumeMs = app.storageBindings.mediaStateStore.get(stateKey)
                    ?.positionMs?.takeIf { it > 0L } ?: startMs
                val inst = app.instanceRegistry.getOrCreate(sourceId)
                    ?: throw IllegalStateException("No provider instance for $sourceId")
                inst.resolvePlayback(itemRefDecoded, resumeMs, app)
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
                OpenTunePlayerScreen(
                    spec = spec!!,
                    mediaStateStore = app.storageBindings.mediaStateStore,
                    mediaStateKey = stateKey,
                    onExit = onExit,
                )
            }
        }
        else -> {
            Text("Loading\u2026", modifier = Modifier.padding(48.dp))
        }
    }
}
