package com.opentune.app.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.opentune.app.OpenTuneApplication
import com.opentune.storage.OpenTuneDatabase

@Composable
fun PlayerRoute(
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    itemRefDecoded: String,
    startMs: Long,
    onExit: () -> Unit,
) {
    val preparer = remember(providerId) { app.providerRegistry.playbackPreparer(providerId) }
    PlayerShell {
        preparer.Render(
            app = app,
            database = database,
            sourceId = sourceId,
            itemRefDecoded = itemRefDecoded,
            startPositionMs = startMs,
            onExit = onExit,
        )
    }
}
