package com.opentune.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.playback.api.OpenTunePlaybackHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val LOG_TAG = "OpenTunePlayerShell"

/** Emby (session hooks): wait for READY up to this long. */
private const val MAX_WAIT_READY_MS = 120_000L

/**
 * SMB / no progress hooks: old SMB player never blocked on READY; strict wait can hang forever
 * if the pipeline never reaches READY after an audio-off retry. Keep a short soft wait only.
 */
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
    exoPlayer: ExoPlayer,
    hooks: OpenTunePlaybackHooks,
    startPositionMs: Long,
    onExit: () -> Unit,
    /** When non-null, current playback position is mirrored locally for resumption. */
    resumeProgressKey: String? = null,
    topBanner: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hooksState = rememberUpdatedState(hooks)
    val onExitState = rememberUpdatedState(onExit)
    val released = remember(exoPlayer) { AtomicBoolean(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    suspend fun shutdown(userInitiatedExit: Boolean) {
        Log.d(LOG_TAG, "shutdown userInitiated=$userInitiatedExit alreadyReleased=${released.get()}")
        if (!released.compareAndSet(false, true)) {
            if (userInitiatedExit) {
                withContext(Dispatchers.Main) { onExitState.value() }
            }
            return
        }
        val pos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
        val rk = resumeProgressKey
        if (rk != null) {
            OpenTunePlaybackResumeStore.writeResumePosition(context.applicationContext, rk, pos)
        }
        hooksState.value.onStop(pos)
        withContext(Dispatchers.Main) { exoPlayer.release() }
        if (userInitiatedExit) {
            withContext(Dispatchers.Main) { onExitState.value() }
        }
    }

    fun playbackRate(): Float = exoPlayer.playbackParameters.speed

    DisposableEffect(exoPlayer, context, resumeProgressKey) {
        val appContext = context.applicationContext
        val rk = resumeProgressKey
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if (rk != null) {
                    OpenTunePlaybackResumeStore.writeSpeed(appContext, rk, parameters.speed)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer, hooks, startPositionMs) {
        Log.d(
            LOG_TAG,
            "readyEffect start startPositionMs=$startPositionMs progressIntervalMs=${hooks.progressIntervalMs()}",
        )
        val strictReadyWait = hooks.progressIntervalMs() > 0L || startPositionMs > 0L
        val maxReadyMs = if (strictReadyWait) MAX_WAIT_READY_MS else MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS
        if (!strictReadyWait) {
            Log.d(LOG_TAG, "readyEffect soft READY wait (no progress hooks / typical SMB)")
        }
        var waitedReadyMs = 0L
        while (exoPlayer.playbackState != Player.STATE_READY && isActive) {
            if (strictReadyWait && waitedReadyMs % 2000L < 32L) {
                val err = withContext(Dispatchers.Main) { exoPlayer.playerError }
                Log.i(
                    LOG_TAG,
                    "waiting STATE_READY waitedMs=$waitedReadyMs playbackState=${exoPlayer.playbackState} " +
                        "isLoading=${exoPlayer.isLoading} playWhenReady=${exoPlayer.playWhenReady} err=${err?.message}",
                )
            }
            delay(32)
            waitedReadyMs += 32
            if (waitedReadyMs >= maxReadyMs) {
                Log.w(
                    LOG_TAG,
                    "timeout waiting STATE_READY after ${waitedReadyMs}ms (strict=$strictReadyWait) " +
                        "state=${exoPlayer.playbackState} err=${exoPlayer.playerError?.message}",
                )
                break
            }
        }
        if (!isActive) {
            Log.d(LOG_TAG, "readyEffect cancelled before seek")
            return@LaunchedEffect
        }
        if (startPositionMs > 0) {
            Log.d(LOG_TAG, "seekTo startPositionMs=$startPositionMs")
            withContext(Dispatchers.Main) { exoPlayer.seekTo(startPositionMs) }
            var n = 0
            while (isActive && n++ < 200) {
                val cur = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
                if (abs(cur - startPositionMs) < 1500) break
                delay(32)
            }
            Log.d(LOG_TAG, "after seek loop iterations=$n position=${exoPlayer.currentPosition}")
        }
        if (!isActive) return@LaunchedEffect
        if (released.get()) {
            Log.d(LOG_TAG, "readyEffect skip onPlaybackReady: already released")
            return@LaunchedEffect
        }
        val pos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
        val rate = withContext(Dispatchers.Main) { playbackRate() }
        Log.d(LOG_TAG, "onPlaybackReady positionMs=$pos rate=$rate")
        hooks.onPlaybackReady(pos, rate)
    }

    LaunchedEffect(exoPlayer, hooks, resumeProgressKey) {
        val interval = hooks.progressIntervalMs()
        val rk = resumeProgressKey
        val appCtx = context.applicationContext
        if (interval > 0L) {
            while (isActive) {
                delay(interval)
                if (!exoPlayer.isPlaying) continue
                if (released.get()) break
                val pos = exoPlayer.currentPosition
                hooksState.value.onProgressTick(pos, playbackRate())
                if (rk != null) {
                    OpenTunePlaybackResumeStore.writeResumePosition(appCtx, rk, pos)
                }
            }
        } else if (rk != null) {
            while (isActive) {
                delay(10_000L)
                if (released.get()) break
                if (exoPlayer.isPlaying) {
                    OpenTunePlaybackResumeStore.writeResumePosition(appCtx, rk, exoPlayer.currentPosition)
                }
            }
        }
    }

    BackHandler {
        val pv = playerViewRef
        if (pv != null && pv.isControllerFullyVisible) {
            pv.hideController()
        } else {
            scope.launch { shutdown(userInitiatedExit = true) }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            runBlocking { shutdown(userInitiatedExit = false) }
        }
    }

    // MediaSession: system sees active playback. FLAG_KEEP_SCREEN_ON: whole time this screen is
    // shown (paused or not) so ATV ambient / idle overlay does not fire while the user stays here.
    // Registered after the shutdown effect so this onDispose runs first and releases the session
    // before ExoPlayer.release().
    DisposableEffect(exoPlayer) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session =
            if (activity != null) {
                MediaSession.Builder(activity, exoPlayer).build()
            } else {
                null
            }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            session?.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OpenTunePlayerView(
            player = exoPlayer,
            modifier = Modifier.fillMaxSize(),
            onPlayerViewBound = { playerViewRef = it },
        )
        topBanner?.invoke(this@Box)
    }
}
