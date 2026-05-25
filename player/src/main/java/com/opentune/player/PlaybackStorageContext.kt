package com.opentune.player

import androidx.compose.runtime.staticCompositionLocalOf
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore

data class PlaybackStorageContext(
    val entryStateStore: EntryStateStore,
    val entryStateKey: EntryStateKey,
    val parentStateKey: EntryStateKey? = null,
    val seriesStateKey: EntryStateKey? = null,
    val seriesSeasonNumber: Int? = null,
    val seriesEpisodeNumber: Int? = null,
    val appConfigStore: AppPrefsStore,
)

val LocalPlaybackStorageContext = staticCompositionLocalOf<PlaybackStorageContext> {
    error("No PlaybackStorageContext provided")
}
