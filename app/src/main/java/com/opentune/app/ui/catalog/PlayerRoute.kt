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
import com.opentune.player.OpenTunePlaybackResumeStore
import com.opentune.player.OpenTunePlayerScreen
import com.opentune.provider.PlaybackResumeAccessor
import com.opentune.provider.PlaybackResolveDeps
import com.opentune.provider.PlaybackSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerRoute(
    app: OpenTuneApplication,
    providerId: String,
    sourceId: Long,
    itemRefDecoded: String,
    startMs: Long,
    onExit: () -> Unit,
) {
    var spec by remember { mutableStateOf<PlaybackSpec?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providerId, sourceId, itemRefDecoded, startMs) {
        spec = null
        error = null
        try {
            spec = withContext(Dispatchers.IO) {
                val resumeAccessor = PlaybackResumeAccessor { rk ->
                    OpenTunePlaybackResumeStore.readResumePosition(app, rk)
                }
                val deps = PlaybackResolveDeps(
                    serverStore = app.storageBindings.serverStore,
                    progressStore = app.storageBindings.progressStore,
                    androidContext = app,
                    resumeAccessor = resumeAccessor,
                )
                app.providerRegistry.provider(providerId).resolvePlayback(
                    deps,
                    sourceId,
                    itemRefDecoded,
                    startMs,
                )
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
                OpenTunePlayerScreen(spec = spec!!, onExit = onExit)
            }
        }
        else -> {
            Text("Loading…", modifier = Modifier.padding(48.dp))
        }
    }
}
