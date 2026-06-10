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

    val exo: ExoPlayer = OpenTuneExoPlayer.createForBundledSources(appContext).player

    private val _spec = MutableStateFlow<PlaybackSpec?>(null)
    val currentSpec: PlaybackSpec? get() = _spec.value
    val currentSpecFlow: StateFlow<PlaybackSpec?> = _spec.asStateFlow()
    
    private val _storageCtx = MutableStateFlow<PlaybackStorageContext?>(null)
    val storageCtx: PlaybackStorageContext? get() = _storageCtx.value
    val storageCtxFlow: StateFlow<PlaybackStorageContext?> = _storageCtx.asStateFlow()

    private var heartbeatJob: Job? = null

    val bufferedMs: Long
        get() {
            val pos = exo.currentPosition
            val buf = exo.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    val bufferedBytes: Long get() = BandwidthTracker.totalBytes

    suspend fun prepare(
        spec: PlaybackSpec,
        storageCtx: PlaybackStorageContext,
        startMs: Long,
    ) {
        val savedSpeed = withContext(Dispatchers.IO) {
            storageCtx.entryStateStore.get(storageCtx.entryStateKey)?.playbackSpeed ?: 1f
        }.coerceIn(0.25f, 4f)

        _storageCtx.value = storageCtx
        _spec.value = spec
        startHeartbeat(spec, storageCtx)

        withContext(Dispatchers.Main) {
            if (exo.playbackState != Player.STATE_IDLE) {
                exo.playbackParameters = PlaybackParameters(savedSpeed)
                if (startMs >= 0L) exo.seekTo(startMs)

                Log.d(SESSION_LOG, "prepare: seekTo=$startMs")
                return@withContext
            }

            Log.d(SESSION_LOG, "prepare: load startMs=$startMs (was state=${exo.playbackState})")
      
            exo.stop()
            Log.d(SESSION_LOG, "prepare: stopped pos=${exo.currentPosition} buf=${exo.bufferedPosition}")
            exo.playWhenReady = false
            exo.playbackParameters = PlaybackParameters(savedSpeed)
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            exo.setMediaSource(spec.toMediaSource(appContext), startMs)
            exo.prepare()
        }
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
    }

    fun play() {
        exo.playWhenReady = true
    }

    fun pause() {
        exo.playWhenReady = false
    }

    private fun stopInternal() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        exo.stop()
        BandwidthTracker.resetTotalBytes()
    }

    fun stop() {
        val pos = exo.currentPosition
        val hooks = _spec.value?.hooks
        stopInternal()
        scope.launch {
            _storageCtx.value?.let { ctx ->
                withContext(Dispatchers.IO) {
                    ctx.entryStateStore.upsertPosition(ctx.entryStateKey, pos)
                }
            }
            hooks?.onStop(pos)
            hooks?.onDispose()
        }
    }

    fun clear() {
        stopInternal()
        exo.release()
    }

    private fun startHeartbeat(spec: PlaybackSpec, storageCtx: PlaybackStorageContext) {
        heartbeatJob?.cancel()
        val hooks = spec.hooks
        val interval = hooks.progressIntervalMs().takeIf { it > 0L } ?: DEFAULT_PROGRESS_INTERVAL_MS
        heartbeatJob = scope.launch {
            var readyReported = false
            while (isActive) {
                delay(interval)
                if(exo.playbackState != Player.STATE_READY) continue
                val pos = exo.currentPosition
                val isPaused = !exo.playWhenReady
                val rate = exo.playbackParameters.speed
                if (!readyReported) {
                    hooks.onPlaybackReady(pos, rate)
                    readyReported = true
                }
                hooks.onProgressTick(pos, rate, isPaused)
                if (!isPaused) {
                    withContext(Dispatchers.IO) {
                        storageCtx.entryStateStore.upsertPosition(storageCtx.entryStateKey, pos)
                    }
                }
            }
        }
    }
}
