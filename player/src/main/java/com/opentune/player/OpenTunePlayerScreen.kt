package com.opentune.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.player.audio.rememberAudioController
import com.opentune.player.menu.rememberPlayerMenu
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val LOG_TAG = "OpenTunePlayerShell"

private const val MAX_WAIT_READY_MS = 120_000L

private const val MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS = 2_500L

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
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
    val exo = remember(instanceKey) { OpenTuneExoPlayer.createForBundledSources(context) }
    val released = remember(instanceKey) { AtomicBoolean(false) }

    val stores = remember { PlayerStores(mediaStateStore, appConfigStore) }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var showAudioUnsupportedBanner by remember { mutableStateOf(false) }

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
    val menu = rememberPlayerMenu(subtitleCtrl.menuEntry, audioCtrl.menuEntry, speedCtrl.menuEntry)

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
        showAudioUnsupportedBanner = false
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

    DisposableEffect(exo, spec) {
        val s = spec
        val fb = s.audioFallbackFactory
        if (s.audioFallbackOnly && fb != null) {
            val fallbackSource = fb.create()
            val listener = exo.createAudioDecodeFallbackListener(
                logTag = OPEN_TUNE_PLAYER_LOG,
                mainHandler = mainHandler,
                media = AudioFallbackMedia.MediaSourcePayload(fallbackSource),
                onAudioDisabled = {
                    if (!s.audioDecodeUnsupportedBanner.isNullOrBlank()) {
                        showAudioUnsupportedBanner = true
                    }
                },
            )
            exo.addListener(listener)
            onDispose { exo.removeListener(listener) }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(exo, instanceKey, spec.hooks) {
        val s = spec
        val interval = s.hooks.progressIntervalMs()
        if (interval > 0L) {
            while (isActive) {
                delay(interval)
                if (!exo.isPlaying) continue
                if (released.get()) break
                val pos = exo.currentPosition
                hooksState.value.onProgressTick(pos, playbackRate())
                withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            }
        } else {
            while (isActive) {
                delay(10_000L)
                if (released.get()) break
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
                if (pv is OpenTuneTvPlayerView && pv.dismissSettingsPopupIfShowing()) {
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

    val bannerText = spec.audioDecodeUnsupportedBanner
    val topBanner: (@Composable BoxScope.() -> Unit)? =
        if (showAudioUnsupportedBanner && !bannerText.isNullOrBlank()) {
            {
                M3Text(
                    text = bannerText,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
        } else {
            null
        }

    Box(modifier = Modifier.fillMaxSize()) {
        OpenTunePlayerView(
            player = exo,
            modifier = Modifier.fillMaxSize(),
            onPlayerViewBound = { playerViewRef = it },
            onSettingsMenu = { menu.open() },
            onDpadKey = when {
                menu.isOpen -> menu.onDpadKey
                subtitleCtrl.isAdjustActive -> subtitleCtrl.adjustDpadKey
                else -> null
            },
            subtitleTranslationYPx = subtitleCtrl.translationYPx,
            subtitleSizeScale = subtitleCtrl.sizeScale,
        )
        topBanner?.invoke(this@Box)
        menu.Menu()
        subtitleCtrl.AdjustOsd()
    }
}

