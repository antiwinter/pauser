package com.opentune.player.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.controller.AudioController
import com.opentune.player.controller.SpeedController
import com.opentune.player.controller.SubtitleController
import com.opentune.player.controller.prepareWithSidecar
import com.opentune.player.controller.rememberAudioController
import com.opentune.player.controller.rememberSpeedController
import com.opentune.player.controller.rememberSubtitleController
import com.opentune.player.controller.resolveSubtitlePreference
import com.opentune.player.controller.subtitleMimeType
import com.opentune.player.LocalPlaybackStorageContext
import com.opentune.player.PlaybackSpec
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val ENGINE_LOG = "PlaybackEngine"
private const val MAX_WAIT_READY_MS = 120_000L
private const val MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS = 2_500L

// ---------------------------------------------------------------------------
// Shared stores holder — used by SubtitleController, AudioController, SpeedController
// ---------------------------------------------------------------------------

internal data class PlayerStores(
    val entryStateStore: EntryStateStore,
    val appConfigStore: AppPrefsStore,
)

// ---------------------------------------------------------------------------
// PlaybackEngine — pure playback logic, no UI
// ---------------------------------------------------------------------------

internal class PlaybackEngine(
    val exo: ExoPlayer,
    val subtitleCtrl: SubtitleController,
    val audioCtrl: AudioController,
    val speedCtrl: SpeedController,
    val trackInfo: State<TrackInfo>,
    val bandwidthMbps: MutableFloatState,
    private val released: AtomicBoolean,
    private val specState: State<PlaybackSpec>,
    private val entryStateStore: EntryStateStore,
    private val entryStateKey: EntryStateKey,
    private val seriesStateKey: EntryStateKey?,
    private val seriesSeasonNumber: Int?,
    private val seriesEpisodeNumber: Int?,
) {
    /** Idempotent — safe to call multiple times (e.g. from BackHandler and onDispose). */
    suspend fun release() {
        val s = specState.value
        Log.d(ENGINE_LOG, "release alreadyReleased=${released.get()}")
        if (!released.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            val pos = withContext(Dispatchers.Main) { exo.currentPosition }
            withContext(Dispatchers.IO) {
                entryStateStore.upsertPosition(entryStateKey, pos)
                if (seriesStateKey != null && seriesSeasonNumber != null && seriesEpisodeNumber != null) {
                    entryStateStore.upsertSeriesProgress(seriesStateKey, seriesSeasonNumber, seriesEpisodeNumber)
                }
            }
            s.hooks.onStop(pos)
            withContext(Dispatchers.Main) { exo.release() }
            s.hooks.onDispose()
        }
    }
}

// ---------------------------------------------------------------------------
// rememberPlaybackEngine — owns all LaunchedEffects / DisposableEffects
// ---------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun rememberPlaybackEngine(
    spec: PlaybackSpec,
    startMs: Long,
    initialSubtitleTrackId: String?,
    @Suppress("UNUSED_PARAMETER") initialAudioTrackId: String?,
    initialSubtitleOffsetFraction: Float,
    initialSubtitleSizeScale: Float,
): PlaybackEngine {
    val storageCtx = LocalPlaybackStorageContext.current
    val entryStateStore = storageCtx.entryStateStore
    val entryStateKey = storageCtx.entryStateKey
    val parentStateKey = storageCtx.parentStateKey
    val seriesStateKey = storageCtx.seriesStateKey
    val seriesSeasonNumber = storageCtx.seriesSeasonNumber
    val seriesEpisodeNumber = storageCtx.seriesEpisodeNumber
    val appConfigStore = storageCtx.appConfigStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instanceKey = entryStateKey

    val specState = rememberUpdatedState(spec)
    val hooksState = rememberUpdatedState(spec.hooks)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val preBufferMs by appConfigStore.preBufferMsFlow
        .collectAsState(initial = AppPrefsStore.DEFAULT_PRE_BUFFER_MS)

    // preBufferMs is a key so the player is recreated if the setting changes.
    val playerWithMeter = remember(instanceKey, preBufferMs) {
        OpenTuneExoPlayer.createForBundledSources(context, preBufferMs)
    }
    val exo = playerWithMeter.player
    val released = remember(instanceKey, preBufferMs) { AtomicBoolean(false) }

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

    val engine = remember(instanceKey, preBufferMs) {
        PlaybackEngine(
            exo = exo,
            subtitleCtrl = subtitleCtrl,
            audioCtrl = audioCtrl,
            speedCtrl = speedCtrl,
            trackInfo = trackInfo,
            bandwidthMbps = bandwidthMbps,
            released = released,
            specState = specState,
            entryStateStore = entryStateStore,
            entryStateKey = instanceKey,
            seriesStateKey = seriesStateKey,
            seriesSeasonNumber = seriesSeasonNumber,
            seriesEpisodeNumber = seriesEpisodeNumber,
        )
    }

    // --- Prepare / seek / ready-wait effect ---
    LaunchedEffect(instanceKey) {
        val s = spec
        released.set(false)
        bandwidthMbps.floatValue = -1f

        val savedSpeed = withContext(Dispatchers.IO) {
            entryStateStore.get(instanceKey.protocol, instanceKey.endpointId, instanceKey.itemRef)
                ?.playbackSpeed ?: 1f
        }.coerceIn(0.25f, 4f)

        val subPref = resolveSubtitlePreference(initialSubtitleTrackId, s)

        withContext(Dispatchers.Main) {
            exo.playbackParameters = PlaybackParameters(savedSpeed)
            val sidecarUri = subPref.externalUri
            if (sidecarUri != null) {
                prepareWithSidecar(
                    context = context,
                    exo = exo,
                    subtitleUri = sidecarUri,
                    mimeType = subtitleMimeType(sidecarUri.toString()),
                    spec = s,
                )
            } else {
                exo.stop()
                exo.setMediaSource(s.toMediaSource(context))
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .apply { subPref.language?.let { setPreferredTextLanguage(it) } }
                    .build()
                exo.playWhenReady = true
                exo.prepare()
            }
        }

        val hooks = s.hooks
        Log.d(ENGINE_LOG, "readyEffect start startMs=$startMs progressIntervalMs=${hooks.progressIntervalMs()}")
        val strictReadyWait = hooks.progressIntervalMs() > 0L || startMs > 0L
        val maxReadyMs = if (strictReadyWait) MAX_WAIT_READY_MS else MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS
        if (!strictReadyWait) Log.d(ENGINE_LOG, "readyEffect soft READY wait (no progress hooks / typical SMB)")
        var waitedReadyMs = 0L
        while (exo.playbackState != Player.STATE_READY && isActive) {
            if (strictReadyWait && waitedReadyMs % 2000L < 32L) {
                val err = withContext(Dispatchers.Main) { exo.playerError }
                Log.i(
                    ENGINE_LOG,
                    "waiting STATE_READY waitedMs=$waitedReadyMs playbackState=${exo.playbackState} " +
                        "isLoading=${exo.isLoading} playWhenReady=${exo.playWhenReady} err=${err?.message}",
                )
            }
            delay(32)
            waitedReadyMs += 32
            if (waitedReadyMs >= maxReadyMs) {
                Log.w(
                    ENGINE_LOG,
                    "timeout waiting STATE_READY after ${waitedReadyMs}ms (strict=$strictReadyWait) " +
                        "state=${exo.playbackState} err=${exo.playerError?.message}",
                )
                break
            }
        }
        if (!isActive) {
            Log.d(ENGINE_LOG, "readyEffect cancelled before seek")
            return@LaunchedEffect
        }
        if (startMs > 0) {
            Log.d(ENGINE_LOG, "seekTo startMs=$startMs")
            withContext(Dispatchers.Main) { exo.seekTo(startMs) }
            var n = 0
            while (isActive && n++ < 200) {
                val cur = withContext(Dispatchers.Main) { exo.currentPosition }
                if (abs(cur - startMs) < 1500) break
                delay(32)
            }
            Log.d(ENGINE_LOG, "after seek loop iterations=$n position=${exo.currentPosition}")
        }
        if (!isActive) return@LaunchedEffect
        if (released.get()) {
            Log.d(ENGINE_LOG, "readyEffect skip onPlaybackReady: already released")
            return@LaunchedEffect
        }
        val pos = withContext(Dispatchers.Main) { exo.currentPosition }
        val rate = withContext(Dispatchers.Main) { exo.playbackParameters.speed }
        Log.d(ENGINE_LOG, "onPlaybackReady positionMs=$pos rate=$rate")
        hooks.onPlaybackReady(pos, rate)
    }

    // --- Progress tick loop ---
    LaunchedEffect(exo, instanceKey, spec.hooks) {
        val progressIntervalMs = spec.hooks.progressIntervalMs()
        val interval = progressIntervalMs.takeIf { it > 0L } ?: 10_000L
        while (isActive) {
            delay(interval)
            if (released.get()) break
            val pos = exo.currentPosition
            val isPaused = !exo.playWhenReady
            hooksState.value.onProgressTick(pos, exo.playbackParameters.speed, isPaused)
            if (!isPaused) {
                withContext(Dispatchers.IO) {
                    entryStateStore.upsertPosition(instanceKey, pos)
                    if (seriesStateKey != null && seriesSeasonNumber != null && seriesEpisodeNumber != null) {
                        entryStateStore.upsertSeriesProgress(seriesStateKey, seriesSeasonNumber, seriesEpisodeNumber)
                    }
                }
            }
        }
    }

    // --- Bandwidth update (1s interval) — read 5s rolling average from BandwidthTracker ---
    LaunchedEffect(instanceKey) {
        while (isActive) {
            delay(1_000)
            if (released.get()) break
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

    // --- Release on dispose ---
    DisposableEffect(exo) {
        onDispose { runBlocking { engine.release() } }
    }

    return engine
}
