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
import com.opentune.player.controller.AudioController
import com.opentune.player.controller.SpeedController
import com.opentune.player.controller.SubtitleController
import com.opentune.player.controller.rememberAudioController
import com.opentune.player.controller.rememberSpeedController
import com.opentune.player.controller.rememberSubtitleController
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ---------------------------------------------------------------------------
// Shared stores holder — used by SubtitleController, AudioController, SpeedController
// ---------------------------------------------------------------------------

internal data class PlayerStores(
    val entryStateStore: EntryStateStore,
    val appConfigStore: AppPrefsStore,
)

// ---------------------------------------------------------------------------
// PlaybackEngine — Compose binding for playback UI/controllers, no media prep ownership
// ---------------------------------------------------------------------------

internal class PlaybackEngine(
    val exo: ExoPlayer,
    val subtitleCtrl: SubtitleController,
    val audioCtrl: AudioController,
    val speedCtrl: SpeedController,
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
// rememberPlaybackEngine — owns Compose listeners/controllers for the player surface
// ---------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun rememberPlaybackEngine(
    spec: PlaybackSpec,
    initialSubtitleTrackId: String?,
    @Suppress("UNUSED_PARAMETER") initialAudioTrackId: String?,
    initialSubtitleOffsetFraction: Float,
    initialSubtitleSizeScale: Float,
    session: PlaybackSession,
): PlaybackEngine {
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
    val subtitleCtrl = rememberSubtitleController(
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
    val audioCtrl = rememberAudioController(
        exo = exo,
        stores = stores,
        entryStateKey = instanceKey,
        parentStateKey = parentStateKey,
        seriesStateKey = seriesStateKey,
    )
    val speedCtrl = rememberSpeedController(
        exo = exo,
        stores = stores,
        entryStateKey = instanceKey,
    )

    val engineKey = instanceKey
    val engine = remember(engineKey) {
        PlaybackEngine(
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
