package com.opentune.player.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.OpenTunePlaybackHooks
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackStorageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SESSION_LOG = "PlaybackSession"
private const val MAX_WAIT_READY_MS = 120_000L
private const val MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS = 2_500L
private const val DEFAULT_PROGRESS_INTERVAL_MS = 10_000L

/**
 * Lifecycle-stable owner of ExoPlayer and media preparation.
 *
 * Host controllers may coordinate content resolution and surface visibility, but all ExoPlayer
 * mutations should go through this session.
 */
@UnstableApi
class PlaybackSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val exoPlayer: ExoPlayer = OpenTuneExoPlayer.createForBundledSources(appContext).player

    private val _currentSpec = MutableStateFlow<PlaybackSpec?>(null)
    val currentSpecFlow: StateFlow<PlaybackSpec?> = _currentSpec.asStateFlow()
    val currentSpec: PlaybackSpec? get() = _currentSpec.value

    private val _storageCtx = MutableStateFlow<PlaybackStorageContext?>(null)
    val storageCtxFlow: StateFlow<PlaybackStorageContext?> = _storageCtx.asStateFlow()
    val storageCtx: PlaybackStorageContext? get() = _storageCtx.value

    var startMs: Long = 0L
        private set

    private var loadedKey: String? = null
    private var currentHooks: OpenTunePlaybackHooks? = null
    private var readyJob: Job? = null
    private var heartbeatJob: Job? = null
    private var stoppedHooks: OpenTunePlaybackHooks? = null

    val isPrepared: Boolean get() = currentSpec != null

    val bufferedDurationMs: Long
        get() {
            if (currentSpec == null) return 0L
            val pos = exoPlayer.currentPosition
            val buf = exoPlayer.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    val loadedBytes: Long get() = BandwidthTracker.totalBytes

    suspend fun prepare(
        spec: PlaybackSpec,
        storageCtx: PlaybackStorageContext,
        startMs: Long,
    ) {
        val key = storageCtx.entryStateKey.itemRef
        val savedSpeed = withContext(Dispatchers.IO) {
            storageCtx.entryStateStore.get(
                storageCtx.entryStateKey.protocol,
                storageCtx.entryStateKey.endpointId,
                storageCtx.entryStateKey.itemRef,
            )?.playbackSpeed ?: 1f
        }.coerceIn(0.25f, 4f)

        _storageCtx.value = storageCtx
        _currentSpec.value = spec
        this.startMs = startMs
        currentHooks = spec.hooks
        stoppedHooks = null
        startHeartbeat(spec, storageCtx)
        startReadyWait(spec)

        withContext(Dispatchers.Main) {
            val alreadyLoaded = key == loadedKey && exoPlayer.playbackState != Player.STATE_IDLE
            if (alreadyLoaded) {
                exoPlayer.playbackParameters = PlaybackParameters(savedSpeed)
                if (startMs >= 0L) exoPlayer.seekTo(startMs)
                Log.d(SESSION_LOG, "prepare: reuse key=$key seekTo=$startMs")
                return@withContext
            }

            Log.d(SESSION_LOG, "prepare: load key=$key startMs=$startMs")
            BandwidthTracker.resetTotalBytes()
            exoPlayer.stop()
            exoPlayer.playWhenReady = false
            exoPlayer.playbackParameters = PlaybackParameters(savedSpeed)
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            exoPlayer.setMediaSource(spec.toMediaSource(appContext), startMs)
            loadedKey = key
            exoPlayer.prepare()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        exoPlayer.trackSelectionParameters = parameters
    }

    fun setPlaybackParameters(parameters: PlaybackParameters) {
        exoPlayer.playbackParameters = parameters
    }

    fun play() {
        stoppedHooks = null
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    suspend fun endPlayback() {
        pause()
        val hooks = currentHooks ?: return
        if (stoppedHooks === hooks) return
        stoppedHooks = hooks
        val pos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
        _storageCtx.value?.let { ctx ->
            withContext(Dispatchers.IO) {
                ctx.entryStateStore.upsertPosition(ctx.entryStateKey, pos)
            }
        }
        hooks.onStop(pos)
    }

    fun stop() {
        pause()
        val hooks = currentHooks
        val shouldCallStop = hooks != null && stoppedHooks !== hooks
        if (shouldCallStop) stoppedHooks = hooks
        val pos = exoPlayer.currentPosition
        val ctx = _storageCtx.value

        readyJob?.cancel()
        readyJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentHooks = null
        exoPlayer.stop()
        _currentSpec.value = null
        _storageCtx.value = null
        startMs = 0L
        loadedKey = null

        hooks?.let {
            scope.launch {
                if (shouldCallStop) {
                    ctx?.let { storageCtx ->
                        withContext(Dispatchers.IO) {
                            storageCtx.entryStateStore.upsertPosition(storageCtx.entryStateKey, pos)
                        }
                    }
                    it.onStop(pos)
                }
                it.onDispose()
            }
        }
    }

    fun release() {
        readyJob?.cancel()
        readyJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentHooks?.onDispose()
        currentHooks = null
        stoppedHooks = null
        exoPlayer.release()
    }

    private fun startReadyWait(spec: PlaybackSpec) {
        readyJob?.cancel()
        val hooks = spec.hooks
        readyJob = scope.launch {
            val strictReadyWait = hooks.progressIntervalMs() > 0L || startMs > 0L
            val maxReadyMs = if (strictReadyWait) MAX_WAIT_READY_MS else MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS
            if (!strictReadyWait) Log.d(SESSION_LOG, "readyWait soft READY wait (no progress hooks / typical SMB)")
            var waitedReadyMs = 0L
            while (exoPlayer.playbackState != Player.STATE_READY && isActive) {
                if (strictReadyWait && waitedReadyMs % 2000L < 32L) {
                    val err = exoPlayer.playerError
                    Log.i(
                        SESSION_LOG,
                        "waiting STATE_READY waitedMs=$waitedReadyMs playbackState=${exoPlayer.playbackState} " +
                            "isLoading=${exoPlayer.isLoading} playWhenReady=${exoPlayer.playWhenReady} err=${err?.message}",
                    )
                }
                delay(32)
                waitedReadyMs += 32
                if (waitedReadyMs >= maxReadyMs) {
                    Log.w(
                        SESSION_LOG,
                        "timeout waiting STATE_READY after ${waitedReadyMs}ms (strict=$strictReadyWait) " +
                            "state=${exoPlayer.playbackState} err=${exoPlayer.playerError?.message}",
                    )
                    break
                }
            }
            if (!isActive) return@launch
            val pos = exoPlayer.currentPosition
            val rate = exoPlayer.playbackParameters.speed
            Log.d(SESSION_LOG, "onPlaybackReady positionMs=$pos rate=$rate")
            hooks.onPlaybackReady(pos, rate)
        }
    }

    private fun startHeartbeat(spec: PlaybackSpec, storageCtx: PlaybackStorageContext) {
        heartbeatJob?.cancel()
        val hooks = spec.hooks
        val interval = hooks.progressIntervalMs().takeIf { it > 0L } ?: DEFAULT_PROGRESS_INTERVAL_MS
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(interval)
                val pos = exoPlayer.currentPosition
                val isPaused = !exoPlayer.playWhenReady
                hooks.onProgressTick(pos, exoPlayer.playbackParameters.speed, isPaused)
                if (!isPaused) {
                    withContext(Dispatchers.IO) {
                        storageCtx.entryStateStore.upsertPosition(storageCtx.entryStateKey, pos)
                    }
                }
            }
        }
    }
}
