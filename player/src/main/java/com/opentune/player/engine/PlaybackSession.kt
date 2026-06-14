package com.opentune.player.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackState
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

    private val _speed = MutableStateFlow(1f)
    val speedFlow: StateFlow<Float> = _speed.asStateFlow()

    private val _subtitleTrackId = MutableStateFlow<String?>(null)
    val subtitleTrackIdFlow: StateFlow<String?> = _subtitleTrackId.asStateFlow()

    private val _audioTrackId = MutableStateFlow<String?>(null)
    val audioTrackIdFlow: StateFlow<String?> = _audioTrackId.asStateFlow()

    private val _subtitleOffsetFraction = MutableStateFlow(0f)
    val subtitleOffsetFractionFlow: StateFlow<Float> = _subtitleOffsetFraction.asStateFlow()

    private val _subtitleSizeScale = MutableStateFlow(1f)
    val subtitleSizeScaleFlow: StateFlow<Float> = _subtitleSizeScale.asStateFlow()

    private var heartbeatJob: Job? = null

    val bufferedMs: Long
        get() {
            val pos = exo.currentPosition
            val buf = exo.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    val bufferedBytes: Long get() = BandwidthTracker.totalBytes

    fun updateSpeed(speed: Float) {
        _speed.value = speed
    }

    fun updateSubtitleTrackId(trackId: String?) {
        _subtitleTrackId.value = trackId
    }

    fun updateAudioTrackId(trackId: String?) {
        _audioTrackId.value = trackId
    }

    fun updateSubtitlePrefs(offsetFraction: Float, sizeScale: Float) {
        _subtitleOffsetFraction.value = offsetFraction
        _subtitleSizeScale.value = sizeScale
    }

    suspend fun prepare(
        spec: PlaybackSpec,
        seed: PlaybackState,
    ) {
        val savedSpeed = seed.speed.coerceIn(0.25f, 4f)

        _speed.value = savedSpeed
        _subtitleTrackId.value = seed.subtitleTrackId
        _audioTrackId.value = seed.audioTrackId
        _subtitleOffsetFraction.value = seed.subtitleOffsetFraction
        _subtitleSizeScale.value = seed.subtitleSizeScale
        _spec.value = spec
        startHeartbeat(spec)

        BandwidthTracker.reset()

        withContext(Dispatchers.Main) {
            Log.d(SESSION_LOG, "prepare: load startMs=${seed.positionMs} (was state=${exo.playbackState})")

            exo.stop()
            Log.d(SESSION_LOG, "prepare: stopped pos=${exo.currentPosition} buf=${exo.bufferedPosition}")
            exo.playWhenReady = false
            exo.playbackParameters = PlaybackParameters(savedSpeed)
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            exo.setMediaSource(spec.toMediaSource(appContext), seed.positionMs)
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
    }

    fun stop() {
        val pos = exo.currentPosition
        val hooks = _spec.value?.hooks
        stopInternal()
        scope.launch {
            hooks?.onStop(pos)
            hooks?.onDispose()
        }
        _spec.value = null
    }

    fun clear() {
        stopInternal()
        exo.release()
    }

    private fun startHeartbeat(spec: PlaybackSpec) {
        heartbeatJob?.cancel()
        val hooks = spec.hooks
        val interval = hooks.progressIntervalMs().takeIf { it > 0L } ?: DEFAULT_PROGRESS_INTERVAL_MS
        heartbeatJob = scope.launch {
            var readyReported = false
            while (isActive) {
                delay(interval)
                if (exo.playbackState != Player.STATE_READY) continue
                val pos = exo.currentPosition
                val isPaused = !exo.playWhenReady
                val rate = exo.playbackParameters.speed
                if (!readyReported) {
                    hooks.onPlaybackReady(pos, rate)
                    readyReported = true
                }
                hooks.onProgressTick(pos, rate, isPaused)
            }
        }
    }
}
