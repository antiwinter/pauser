package com.opentune.app.playback

import androidx.compose.runtime.Composable
import com.opentune.app.OpenTuneApplication
import com.opentune.storage.OpenTuneDatabase

/**
 * Source-specific preparation for unified catalog [com.opentune.app.ui.catalog.PlayerRoute].
 * Resolved via [com.opentune.app.OpenTuneApplication.providerRegistry].
 */
interface PlaybackPreparer {
    @Composable
    fun Render(
        app: OpenTuneApplication,
        database: OpenTuneDatabase,
        sourceId: Long,
        itemRefDecoded: String,
        startPositionMs: Long,
        onExit: () -> Unit,
    )
}
