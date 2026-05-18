package com.opentune.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.opentune.player.controller.AudioController
import com.opentune.player.controller.SpeedController
import com.opentune.player.controller.SubtitleController
import com.opentune.player.controller.rememberAudioController
import com.opentune.player.controller.rememberSpeedController
import com.opentune.player.controller.rememberSubtitleController
import com.opentune.player.controller.subtitleMimeType
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.AppConfigStore
import com.opentune.storage.MediaStateKey
import com.opentune.storage.UserMediaStateStore
import com.opentune.storage.upsertPosition
import com.opentune.storage.upsertSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
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
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: AppConfigStore?,
)

// ---------------------------------------------------------------------------
// Track info state exposed by the engine for OSD rendering
// ---------------------------------------------------------------------------

internal data class TrackInfo(
    val videoMime: String? = null,
    val videoDecoderName: String? = null,
    val audioMime: String? = null,
    val audioDecoderName: String? = null,
)

// ---------------------------------------------------------------------------
// Subtitle resolution helpers
// ---------------------------------------------------------------------------

internal data class SubtitlePreference(
    val externalUri: Uri? = null,
    val language: String? = null,
)

@UnstableApi
internal fun resolveSubtitlePreference(
    savedId: String?,
    spec: PlaybackSpec,
): SubtitlePreference {
    if (savedId == null) return SubtitlePreference()
    if (spec.subtitleTracks.isNotEmpty()) {
        val track = spec.subtitleTracks.find { it.trackId == savedId }
        if (track != null) {
            return if (track.externalRef != null) {
                SubtitlePreference(externalUri = Uri.parse(track.externalRef!!), language = track.language)
            } else {
                SubtitlePreference(language = track.language)
            }
        }
    }
    // ExoPlayer-native ID like "exo_<groupId>" — language unknown, let ExoPlayer auto-select.
    return SubtitlePreference()
}

@UnstableApi
internal suspend fun prepareWithSidecar(
    context: Context,
    exo: ExoPlayer,
    subtitleUri: Uri,
    mimeType: String,
    spec: PlaybackSpec,
) {
    val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration
        .Builder(subtitleUri)
        .setMimeType(mimeType)
        .build()
    val httpFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(spec.headers)
    val subtitleSource = SingleSampleMediaSource
        .Factory(DefaultDataSource.Factory(context, httpFactory))
        .createMediaSource(subtitleConfig, C.TIME_UNSET)
    val mergedSource = MergingMediaSource(spec.toMediaSource(context), subtitleSource)
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setSelectUndeterminedTextLanguage(true)
        .build()
    exo.setMediaSource(mergedSource)
    exo.playWhenReady = true
    exo.prepare()
}

// ---------------------------------------------------------------------------
// PlaybackEngine — pure playback logic, no UI
// ---------------------------------------------------------------------------

internal class PlaybackEngine(
    val exo: ExoPlayer,
    val subtitleCtrl: SubtitleController,
    val audioCtrl: AudioController,
    val speedCtrl: SpeedController,
    private val trackInfoState: MutableState<TrackInfo>,
    val bandwidthMbps: MutableFloatState,
    private val released: AtomicBoolean,
    private val specState: State<PlaybackSpec>,
    private val mediaStateStore: UserMediaStateStore,
    private val mediaStateKey: MediaStateKey,
) {
    val trackInfo: State<TrackInfo> get() = trackInfoState

    /** Idempotent — safe to call multiple times (e.g. from BackHandler and onDispose). */
    suspend fun release() {
        val s = specState.value
        Log.d(ENGINE_LOG, "release alreadyReleased=${released.get()}")
        if (!released.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            val pos = withContext(Dispatchers.Main) { exo.currentPosition }
            withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(mediaStateKey, pos) }
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
    mediaStateStore: UserMediaStateStore,
    mediaStateKey: MediaStateKey,
    appConfigStore: AppConfigStore?,
    initialSubtitleTrackId: String?,
    @Suppress("UNUSED_PARAMETER") initialAudioTrackId: String?,
    initialSubtitleOffsetFraction: Float,
    initialSubtitleSizeScale: Float,
): PlaybackEngine {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instanceKey = mediaStateKey

    val specState = rememberUpdatedState(spec)
    val hooksState = rememberUpdatedState(spec.hooks)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val preBufferMs by (appConfigStore?.preBufferMsFlow
        ?: flowOf(AppConfigStore.DEFAULT_PRE_BUFFER_MS))
        .collectAsState(initial = AppConfigStore.DEFAULT_PRE_BUFFER_MS)

    // preBufferMs is a key so the player is recreated if the setting changes.
    // fallbackSelector is null when DECODER_FALLBACK_ENABLED = false.
    val fallbackSelector = rememberFallbackCodecSelector(instanceKey, preBufferMs)
    val playerWithMeter = remember(instanceKey, preBufferMs) {
        OpenTuneExoPlayer.createForBundledSources(
            context,
            preBufferMs,
            fallbackSelector?.selector ?: MediaCodecSelector.DEFAULT,
        )
    }
    val exo = playerWithMeter.player
    val bandwidthMeter = playerWithMeter.bandwidthMeter
    val released = remember(instanceKey, preBufferMs) { AtomicBoolean(false) }

    val stores = remember { PlayerStores(mediaStateStore, appConfigStore) }
    val trackInfoState = remember(instanceKey) { mutableStateOf(TrackInfo()) }
    val bandwidthMbps = remember(instanceKey) { mutableFloatStateOf(-1f) }

    val subtitleCtrl = rememberSubtitleController(
        exo = exo,
        spec = spec,
        stores = stores,
        mediaStateKey = instanceKey,
        initialTrackId = initialSubtitleTrackId,
        initialOffsetFraction = initialSubtitleOffsetFraction,
        initialSizeScale = initialSubtitleSizeScale,
    )
    val audioCtrl = rememberAudioController(
        exo = exo,
        stores = stores,
        mediaStateKey = instanceKey,
    )
    val speedCtrl = rememberSpeedController(
        exo = exo,
        stores = stores,
        mediaStateKey = instanceKey,
    )

    val engine = remember(instanceKey, preBufferMs) {
        PlaybackEngine(
            exo = exo,
            subtitleCtrl = subtitleCtrl,
            audioCtrl = audioCtrl,
            speedCtrl = speedCtrl,
            trackInfoState = trackInfoState,
            bandwidthMbps = bandwidthMbps,
            released = released,
            specState = specState,
            mediaStateStore = mediaStateStore,
            mediaStateKey = instanceKey,
        )
    }

    // --- Prepare / seek / ready-wait effect ---
    LaunchedEffect(instanceKey) {
        val s = spec
        released.set(false)
        trackInfoState.value = TrackInfo()
        bandwidthMbps.floatValue = -1f

        val savedSpeed = withContext(Dispatchers.IO) {
            mediaStateStore.get(instanceKey.protocol, instanceKey.sourceId, instanceKey.itemRef)
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

    // --- Speed listener ---
    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                scope.launch(Dispatchers.IO) {
                    mediaStateStore.upsertSpeed(instanceKey, parameters.speed)
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // --- Track info (MIME types + decoder names for InfoOsd) ---
    // MIME types come from onTracksChanged. Decoder names come from AnalyticsListener
    // callbacks which fire when a decoder is initialized — this works for both the normal path
    // and for fallback retries (each retry re-initializes the decoder, firing again).
    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                var vm: String? = null
                var am: String? = null
                for (group in tracks.groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val fmt = group.getTrackFormat(i)
                        when (group.type) {
                            // Only update when a track is actively selected; preserve last-known
                            // value when the track is disabled so the OSD can still show it.
                            C.TRACK_TYPE_VIDEO -> vm = fmt.sampleMimeType
                            C.TRACK_TYPE_AUDIO -> am = fmt.sampleMimeType
                        }
                        break
                    }
                }
                mainHandler.post {
                    val current = trackInfoState.value
                    trackInfoState.value = current.copy(
                        videoMime = vm ?: current.videoMime,
                        audioMime = am ?: current.audioMime,
                    )
                }
            }
        }

        val analyticsListener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                trackInfoState.value = trackInfoState.value.copy(videoDecoderName = decoderName)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                trackInfoState.value = trackInfoState.value.copy(audioDecoderName = decoderName)
            }
        }

        exo.addListener(listener)
        exo.addAnalyticsListener(analyticsListener)
        onDispose {
            exo.removeListener(listener)
            exo.removeAnalyticsListener(analyticsListener)
        }
    }

    // --- Decoder retry (no-op when DECODER_FALLBACK_ENABLED = false) ---
    FallbackEffect(
        exo = exo,
        instanceKey = instanceKey,
        selector = fallbackSelector,
        specState = specState,
        trackInfoState = trackInfoState,
        mainHandler = mainHandler,
        context = context,
    )

    // --- Progress tick loop + bandwidth meter ---
    // DefaultBandwidthMeter only receives transfer events from ExoPlayer's DataSource layer.
    // SMB uses SmbJ directly (not ExoPlayer's DataSource), so getBitrateEstimate() always
    // returns -1 for SMB sources — the mbps field in InfoOsd will not be displayed for SMB.
    LaunchedEffect(exo, instanceKey, spec.hooks) {
        val progressIntervalMs = spec.hooks.progressIntervalMs()
        val interval = progressIntervalMs.takeIf { it > 0L } ?: 10_000L
        while (isActive) {
            delay(interval)
            if (released.get()) break
            bandwidthMbps.floatValue = bandwidthMeter.getBitrateEstimate() / 1_000_000f
            val pos = exo.currentPosition
            val isPaused = !exo.playWhenReady
            hooksState.value.onProgressTick(pos, exo.playbackParameters.speed, isPaused)
            if (!isPaused) {
                withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            }
        }
    }

    // --- Release on dispose ---
    DisposableEffect(exo) {
        onDispose { runBlocking { engine.release() } }
    }

    return engine
}
