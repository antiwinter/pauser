package com.opentune.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.inputmethod.InputMethodManager
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.opentune.player.audio.rememberAudioController
import com.opentune.player.menu.rememberMenuOverlay
import com.opentune.player.speed.rememberSpeedController
import com.opentune.player.subtitle.rememberSubtitleController
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.DataStoreAppConfigStore
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

internal data class PlayerStores(
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: DataStoreAppConfigStore?,
)

private const val LOG_TAG = "OpenTunePlayerShell"

private const val PLAYER_LOG = "OpenTunePlayer"

private const val MAX_WAIT_READY_MS = 120_000L

private const val MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS = 2_500L

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun PlaybackException.causeChainContains(vararg keywords: String): Boolean {
    var t: Throwable? = this
    while (t != null) {
        val msg = t.message ?: ""
        if (keywords.any { msg.contains(it, ignoreCase = true) }) return true
        t = t.cause
    }
    return false
}

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun OpenTunePlayerScreen(
    spec: PlaybackSpec,
    mediaStateStore: UserMediaStateStore,
    mediaStateKey: MediaStateKey,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
    appConfigStore: DataStoreAppConfigStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specState = rememberUpdatedState(spec)
    val onExitState = rememberUpdatedState(onExit)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val instanceKey = mediaStateKey

    val preBufferMs by (appConfigStore?.preBufferMsFlow
        ?: flowOf(DataStoreAppConfigStore.DEFAULT_PRE_BUFFER_MS))
        .collectAsState(initial = DataStoreAppConfigStore.DEFAULT_PRE_BUFFER_MS)

    // preBufferMs is a key so the player is recreated if the setting changes. If a settings UI
    // is added that allows changing the buffer duration during playback, this will cause a
    // mid-playback player teardown and reconstruction — acceptable, but worth keeping in mind.
    val playerWithMeter = remember(instanceKey, preBufferMs) {
        OpenTuneExoPlayer.createForBundledSources(context, preBufferMs)
    }
    val exo = playerWithMeter.player
    val bandwidthMeter = playerWithMeter.bandwidthMeter
    val released = remember(instanceKey, preBufferMs) { AtomicBoolean(false) }

    val stores = remember { PlayerStores(mediaStateStore, appConfigStore) }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var audioDisabled by remember { mutableStateOf(false) }
    var videoDisabled by remember { mutableStateOf(false) }
    var videoMime by remember { mutableStateOf<String?>(null) }
    var audioMime by remember { mutableStateOf<String?>(null) }
    var mbps by remember(instanceKey) { mutableStateOf(0f) }

    // Dismiss any IME left open from a previous screen (e.g. server-add / search text fields).
    // hideSoftInputFromWindow also ends the IME's input connection, preventing it from
    // reappearing when the SurfaceView surface is created on first frame.
    val rootView = LocalView.current
    LaunchedEffect(Unit) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    // --- Controllers ---
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
        initialTrackId = null,
    )
    val speedCtrl = rememberSpeedController(
        exo = exo,
        stores = stores,
        mediaStateKey = instanceKey,
    )
    val menu = rememberMenuOverlay(subtitleCtrl.menuEntry, audioCtrl.menuEntry, speedCtrl.menuEntry)

    // --- Playback lifecycle ---

    suspend fun shutdown(userInitiatedExit: Boolean) {
        val s = specState.value
        Log.d(LOG_TAG, "shutdown userInitiated=$userInitiatedExit alreadyReleased=${released.get()}")
        if (!released.compareAndSet(false, true)) {
            if (userInitiatedExit) {
                withContext(Dispatchers.Main) { onExitState.value() }
            }
            return
        }
        withContext(NonCancellable) {
            val pos = withContext(Dispatchers.Main) { exo.currentPosition }
            withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            s.hooks.onStop(pos)
            withContext(Dispatchers.Main) { exo.release() }
            s.onPlaybackDispose()
        }
        if (userInitiatedExit) {
            withContext(Dispatchers.Main) { onExitState.value() }
        }
    }

    fun playbackRate(): Float = exo.playbackParameters.speed

    val hooksState = rememberUpdatedState(spec.hooks)

    LaunchedEffect(instanceKey) {
        val s = spec
        released.set(false)
        audioDisabled = false
        videoDisabled = false
        videoMime = null
        audioMime = null
        val savedSpeed = withContext(Dispatchers.IO) {
            mediaStateStore.get(
                instanceKey.providerType,
                instanceKey.sourceId,
                instanceKey.itemRef,
            )?.playbackSpeed ?: 1f
        }.coerceIn(0.25f, 4f)
        withContext(Dispatchers.Main) {
            exo.playbackParameters = PlaybackParameters(savedSpeed)
            exo.stop()
            exo.setMediaSource(s.mediaSourceFactory.create())
            exo.playWhenReady = true
            exo.prepare()
        }

        val hooks = s.hooks
        Log.d(
            LOG_TAG,
            "readyEffect start initialPositionMs=${s.initialPositionMs} progressIntervalMs=${hooks.progressIntervalMs()}",
        )
        val strictReadyWait = hooks.progressIntervalMs() > 0L || s.initialPositionMs > 0L
        val maxReadyMs = if (strictReadyWait) MAX_WAIT_READY_MS else MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS
        if (!strictReadyWait) {
            Log.d(LOG_TAG, "readyEffect soft READY wait (no progress hooks / typical SMB)")
        }
        var waitedReadyMs = 0L
        while (exo.playbackState != Player.STATE_READY && isActive) {
            if (strictReadyWait && waitedReadyMs % 2000L < 32L) {
                val err = withContext(Dispatchers.Main) { exo.playerError }
                Log.i(
                    LOG_TAG,
                    "waiting STATE_READY waitedMs=$waitedReadyMs playbackState=${exo.playbackState} " +
                        "isLoading=${exo.isLoading} playWhenReady=${exo.playWhenReady} err=${err?.message}",
                )
            }
            delay(32)
            waitedReadyMs += 32
            if (waitedReadyMs >= maxReadyMs) {
                Log.w(
                    LOG_TAG,
                    "timeout waiting STATE_READY after ${waitedReadyMs}ms (strict=$strictReadyWait) " +
                        "state=${exo.playbackState} err=${exo.playerError?.message}",
                )
                break
            }
        }
        if (!isActive) {
            Log.d(LOG_TAG, "readyEffect cancelled before seek")
            return@LaunchedEffect
        }
        if (s.initialPositionMs > 0) {
            Log.d(LOG_TAG, "seekTo initialPositionMs=${s.initialPositionMs}")
            withContext(Dispatchers.Main) { exo.seekTo(s.initialPositionMs) }
            var n = 0
            while (isActive && n++ < 200) {
                val cur = withContext(Dispatchers.Main) { exo.currentPosition }
                if (abs(cur - s.initialPositionMs) < 1500) break
                delay(32)
            }
            Log.d(LOG_TAG, "after seek loop iterations=$n position=${exo.currentPosition}")
        }
        if (!isActive) return@LaunchedEffect
        if (released.get()) {
            Log.d(LOG_TAG, "readyEffect skip onPlaybackReady: already released")
            return@LaunchedEffect
        }
        val pos = withContext(Dispatchers.Main) { exo.currentPosition }
        val rate = withContext(Dispatchers.Main) { playbackRate() }
        Log.d(LOG_TAG, "onPlaybackReady positionMs=$pos rate=$rate")
        hooks.onPlaybackReady(pos, rate)
    }

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

    DisposableEffect(exo, instanceKey) {
        val audioGate = AtomicBoolean(false)
        val videoGate = AtomicBoolean(false)

        fun attemptFallback(
            gate: AtomicBoolean,
            trackType: Int,
            label: String,
            error: PlaybackException,
            setDisabled: () -> Unit,
        ) {
            if (!gate.compareAndSet(false, true)) {
                Log.w(PLAYER_LOG, "$label error after in-place retry; ignoring code=${error.errorCode}")
                return
            }
            Log.w(PLAYER_LOG, "$label decode failed; disabling. code=${error.errorCode}", error)
            mainHandler.post {
                setDisabled()
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                    .build()
                exo.stop()
                exo.setMediaSource(specState.value.mediaSourceFactory.create())
                exo.playWhenReady = true
                exo.prepare()
                Log.d(PLAYER_LOG, "in-place $label-off prepare issued")
            }
        }

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
                            C.TRACK_TYPE_VIDEO -> vm = fmt.sampleMimeType
                            C.TRACK_TYPE_AUDIO -> am = fmt.sampleMimeType
                        }
                        break
                    }
                }
                mainHandler.post { videoMime = vm; audioMime = am }
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") ->
                        attemptFallback(audioGate, C.TRACK_TYPE_AUDIO, "audio", error) { audioDisabled = true }
                    error.causeChainContains("MediaCodecVideoRenderer") ->
                        attemptFallback(videoGate, C.TRACK_TYPE_VIDEO, "video", error) { videoDisabled = true }
                    else -> Log.e(PLAYER_LOG, "onPlayerError code=${error.errorCode} msg=${error.message}", error)
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // DefaultBandwidthMeter only receives transfer events from ExoPlayer's DataSource layer.
    // SMB uses SmbJ directly (not ExoPlayer's DataSource), so getBitrateEstimate() always
    // returns -1 for SMB sources — MbpsOverlay will remain hidden, which is the correct behaviour.
    LaunchedEffect(exo, instanceKey, spec.hooks) {
        val s = spec
        val interval = s.hooks.progressIntervalMs()
        if (interval > 0L) {
            while (isActive) {
                delay(interval)
                if (!exo.isPlaying) continue
                if (released.get()) break
                val pos = exo.currentPosition
                mbps = bandwidthMeter.getBitrateEstimate() / 1_000_000f
                hooksState.value.onProgressTick(pos, playbackRate())
                withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            }
        } else {
            while (isActive) {
                delay(10_000L)
                if (released.get()) break
                mbps = bandwidthMeter.getBitrateEstimate() / 1_000_000f
                if (exo.isPlaying) {
                    val pos = exo.currentPosition
                    withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
                }
            }
        }
    }

    BackHandler {
        when {
            subtitleCtrl.handleBack() -> {}
            menu.handleBack() -> {}
            else -> {
                val pv = playerViewRef
                if (pv is OpenTuneTvPlayerView && pv.dismissMenuPopupIfShowing()) {
                    return@BackHandler
                }
                if (pv != null && pv.isControllerFullyVisible) {
                    pv.hideController()
                } else {
                    scope.launch { shutdown(userInitiatedExit = true) }
                }
            }
        }
    }

    DisposableEffect(exo) {
        onDispose {
            runBlocking { shutdown(userInitiatedExit = false) }
        }
    }

    DisposableEffect(exo) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session =
            if (activity != null) {
                MediaSession.Builder(activity, exo).build()
            } else {
                null
            }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            session?.release()
        }
    }

    // --- UI ---

    val infoOsd = rememberInfoOsd(
        instanceKey = instanceKey,
        spec = spec,
        videoMime = videoMime,
        videoDisabled = videoDisabled,
        audioMime = audioMime,
        audioDisabled = audioDisabled,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        OpenTunePlayerView(
            player = exo,
            modifier = Modifier.fillMaxSize(),
            onPlayerViewBound = { playerViewRef = it },
            onOpenMenu = { menu.open() },
            onKey = { event ->
                when {
                    menu.isOpen -> menu.onKey?.invoke(event) == true
                    subtitleCtrl.isAdjustActive -> {
                        if (event.action == KeyEvent.ACTION_DOWN) subtitleCtrl.adjustKey(event.keyCode)
                        true
                    }
                    // No overlay: UP toggles infoOSD (don't consume — let view show controller)
                    event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                        infoOsd.toggle()
                        false
                    }
                    else -> false
                }
            },
            subtitleTranslationYPx = subtitleCtrl.translationYPx,
            subtitleSizeScale = subtitleCtrl.sizeScale,
        )
        menu.Overlay()
        subtitleCtrl.AdjustOsd()
        infoOsd.Osd()
        MbpsOverlay(mbps = mbps)
    }
}
