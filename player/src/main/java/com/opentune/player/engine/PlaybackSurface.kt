package com.opentune.player.engine

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.LocalPlaybackStorageContext
import com.opentune.player.PlaybackSpec
import com.opentune.player.manager.AudioManager
import com.opentune.player.manager.SpeedManager
import com.opentune.player.manager.SubtitleManager
import com.opentune.player.manager.rememberAudioManager
import com.opentune.player.manager.rememberSpeedManager
import com.opentune.player.manager.rememberSubtitleManager
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ---------------------------------------------------------------------------
// Shared stores holder — used by SubtitleManager, AudioManager, SpeedManager
// ---------------------------------------------------------------------------

internal data class PlayerStores(
    val entryStateStore: EntryStateStore,
    val appConfigStore: AppPrefsStore,
)

// ---------------------------------------------------------------------------
// PlaybackSurface — Compose binding for playback UI/managers, no media prep ownership
// ---------------------------------------------------------------------------

internal class PlaybackSurface(
    val exo: ExoPlayer,
    val subtitleCtrl: SubtitleManager,
    val audioCtrl: AudioManager,
    val speedCtrl: SpeedManager,
    val trackInfo: State<TrackInfo>,
    val bandwidthMbps: MutableFloatState,
    private val session: PlaybackSession,
) {
    /** User is leaving the full-screen surface but keeping the prepared session alive. */
    fun leaveSurface() {
        session.pause()
    }
}

// ---------------------------------------------------------------------------
// rememberPlaybackSurface — owns Compose listeners/managers for the player surface
// ---------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun rememberPlaybackSurface(
    spec: PlaybackSpec,
    initialSubtitleTrackId: String?,
    @Suppress("UNUSED_PARAMETER") initialAudioTrackId: String?,
    initialSubtitleOffsetFraction: Float,
    initialSubtitleSizeScale: Float,
    session: PlaybackSession,
): PlaybackSurface {
    val storageCtx = LocalPlaybackStorageContext.current
    val entryStateStore = storageCtx.entryStateStore
    val entryStateKey = storageCtx.entryStateKey
    val parentStateKey = storageCtx.parentStateKey
    val seriesStateKey = storageCtx.seriesStateKey
    val appConfigStore = storageCtx.appConfigStore
    val context = LocalContext.current
    val instanceKey = entryStateKey
    val exo = session.exo

    val specState = rememberUpdatedState(spec)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val stores = remember { PlayerStores(entryStateStore, appConfigStore) }
    val trackInfo = rememberTrackInfo(exo, instanceKey, mainHandler)
    val bandwidthMbps = remember(instanceKey) { mutableFloatStateOf(-1f) }
    val subtitleCtrl = rememberSubtitleManager(
        exo = exo,
        spec = spec,
        stores = stores,
        entryStateKey = instanceKey,
        parentStateKey = parentStateKey,
        seriesStateKey = seriesStateKey,
        initialTrackId = initialSubtitleTrackId,
        initialOffsetFraction = initialSubtitleOffsetFraction,
        initialSizeScale = initialSubtitleSizeScale,
    )
    val audioCtrl = rememberAudioManager(
        exo = exo,
        stores = stores,
        entryStateKey = instanceKey,
        parentStateKey = parentStateKey,
        seriesStateKey = seriesStateKey,
    )
    val speedCtrl = rememberSpeedManager(
        exo = exo,
        stores = stores,
        entryStateKey = instanceKey,
    )

    val engineKey = instanceKey
    val engine = remember(engineKey) {
        PlaybackSurface(
            exo = exo,
            subtitleCtrl = subtitleCtrl,
            audioCtrl = audioCtrl,
            speedCtrl = speedCtrl,
            trackInfo = trackInfo,
            bandwidthMbps = bandwidthMbps,
            session = session,
        )
    }

    // --- Bandwidth update (1s interval) — read 5s rolling average from BandwidthTracker ---
    LaunchedEffect(instanceKey) {
        while (isActive) {
            delay(1_000)
            bandwidthMbps.floatValue = BandwidthTracker.mbps
        }
    }

    // --- Track-level fallback: video fail → audio-only, audio fail → video-only ---
    TrackFallbackEffect(
        exo = exo,
        instanceKey = instanceKey,
        specState = specState,
        trackInfoState = trackInfo,
        mainHandler = mainHandler,
        context = context,
    )

    return engine
}
