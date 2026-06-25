package com.opentune.player.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.EntryStateKeys
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackState
import com.opentune.player.PlayingState
import com.opentune.player.manager.AudioManager
import com.opentune.player.manager.FallbackManager
import com.opentune.player.manager.PlaybackManager
import com.opentune.player.manager.SpeedManager
import com.opentune.player.manager.subtitle.SubtitleManager
import com.opentune.player.manager.TrackInfo
import com.opentune.player.manager.TrackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SESSION_LOG = "PlaybackSession"
private const val DEFAULT_PROGRESS_INTERVAL_MS = 10_000L

/**
 * Lifecycle-stable owner of ExoPlayer and media preparation.
 *
 * Host controllers may coordinate content resolution and surface visibility, but all ExoPlayer
 * mutations should go through this session or its manager extensions. In particular, this is the
 * ONLY place that writes [ExoPlayer.trackSelectionParameters] (via manager extensions like
 * [selectAudio] / [selectSubtitle] / [setVideoEnabled] / [setAudioEnabled]) or rebuilds the media
 * source (via the internal [rebuildKeepingPosition]).
 *
 * All [PlaybackManager] instances are instantiated here and their listeners are attached in [init]
 * so that one-shot decoder-init callbacks are never missed. [PlaybackManager.onPrepare] is called
 * during [prepare] to reset per-entry state; [PlaybackManager.onDispose] during [stopInternal].
 */
@UnstableApi
class PlaybackSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val exo: ExoPlayer = OpenTuneExoPlayer.createForBundledSources(appContext).player

    // ---------------------------------------------------------------------------
    // Managers: each owns a business domain (tracks, fallback, subtitles, audio, speed, HDR).
    // ---------------------------------------------------------------------------

    /** Surface-referenced for its subtitle/adjust UI state; also drives [onPrepare] restoration. */
    internal val subtitleManager = SubtitleManager(this)

    internal val managers: List<PlaybackManager> = listOf(
        TrackManager(this), // tracks go first !important
        FallbackManager(this),
        subtitleManager,
        AudioManager(this),
        SpeedManager(this),
    )

    init {
        managers.forEach { m ->
            m.listeners?.forEach { exo.addListener(it) }
            m.analyticsListeners?.forEach { exo.addAnalyticsListener(it) }
        }
    }

    // ---------------------------------------------------------------------------
    // Player-derived state (flows owned by session, populated by TrackManager)
    // ---------------------------------------------------------------------------

    private val _trackInfo = MutableStateFlow(TrackInfo())
    internal val trackInfoFlow: StateFlow<TrackInfo> = _trackInfo.asStateFlow()

    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracksFlow: StateFlow<Tracks> = _tracks.asStateFlow()

    /** Direct mutator for [_tracks]; TrackManager writes here from onTracksChanged/onPrepare. */
    internal var tracks: Tracks
        get() = _tracks.value
        set(value) { _tracks.value = value }

    /** Mutator for [trackInfoFlow]; used by TrackManager and FallbackManager ('err'). */
    internal fun updateTrackInfo(transform: (TrackInfo) -> TrackInfo) {
        _trackInfo.value = transform(_trackInfo.value)
    }

    // ---------------------------------------------------------------------------
    // Playback state (single source of truth derived from [_spec])
    // ---------------------------------------------------------------------------

    private val _spec = MutableStateFlow<PlaybackSpec?>(null)
    val currentSpec: PlaybackSpec? get() = _spec.value
    val currentSpecFlow: StateFlow<PlaybackSpec?> = _spec.asStateFlow()

    /** The PlayerView currently rendering this session, set by the surface. Null when offscreen. */
    var view: androidx.media3.ui.PlayerView? = null

    private fun <T> stateField(default: T, selector: (PlaybackState) -> T): StateFlow<T> =
        _spec.map { it?.state?.let(selector) ?: default }
            .stateIn(scope, SharingStarted.Eagerly, _spec.value?.state?.let(selector) ?: default)

    val speedFlow: StateFlow<Float> = stateField(1f) { it.speed }
    val subtitleTrackIdFlow: StateFlow<String?> = stateField(null) { it.subtitleTrackId }
    val audioTrackIdFlow: StateFlow<String?> = stateField(null) { it.audioTrackId }

    private var heartbeatJob: Job? = null

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    val bufferedMs: Long
        get() {
            val pos = exo.currentPosition
            val buf = exo.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    val bufferedBytes: Long get() = BandwidthTracker.totalBytes

    /** Copies [transform] into the live spec's state so derived flows and rebuilds see it at once. */
    internal fun updateState(transform: (PlaybackState) -> PlaybackState) {
        val spec = _spec.value ?: return
        _spec.value = spec.copy(state = transform(spec.state))
    }

    fun updateSpeed(speed: Float) {
        updateState { it.copy(speed = speed) }
        notifyEntryState(EntryStateKeys.SPEED, speed.toString())
    }

    fun updateSubtitlePrefs(offsetFraction: Float, sizeScale: Float) {
        updateState { it.copy(subtitleOffsetFraction = offsetFraction, subtitleSizeScale = sizeScale) }
        notifyEntryState(EntryStateKeys.SUBTITLE_OFFSET_FRACTION, offsetFraction.toString())
        notifyEntryState(EntryStateKeys.SUBTITLE_SIZE_SCALE, sizeScale.toString())
    }

    /**
     * Rebuilds the media source from the current spec and re-prepares, preserving playback position.
     * [toMediaSource] derives the active sidecar subtitle from the spec's saved track id, so callers
     * that mutate subtitle state first (see [selectSubtitle]) get the right source. Internal: every
     * rebuild path routes through one of the manager extensions.
     */
    internal fun rebuildKeepingPosition() {
        val spec = _spec.value ?: return
        val pos = exo.currentPosition
        exo.setMediaSource(spec.toMediaSource(appContext))
        exo.playWhenReady = true
        exo.prepare()
        exo.seekTo(pos)
    }

    fun notifyEntryState(key: String, value: String?) {
        scope.launch {
            _spec.value?.updateEntryState(key, value)
        }
    }

    suspend fun prepare(spec: PlaybackSpec) {
        emitTeardown(PlayingState.STOPPED)
        val state = spec.state
        _spec.value = spec
        _spec.value?.updateEntryState(EntryStateKeys.SOURCE_INDEX, state.sourceIndex.toString())
        withContext(Dispatchers.Main) {
            Log.d(SESSION_LOG, "prepare: load startMs=${state.positionMs} (was state=${exo.playbackState})")

            managers.forEach { it.onPrepare() }
            BandwidthTracker.reset()

            exo.stop()
            exo.playWhenReady = false

            exo.setMediaSource(spec.toMediaSource(appContext), state.positionMs)
            exo.prepare()
            startHeartbeat()
        }
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
    }

    /** Forwarded from the surface's AndroidView `update` block; lets managers re-apply
     *  view-derived state each recomposition (see [PlaybackManager.onViewUpdate]). */
    internal fun onViewUpdate() {
        managers.forEach { it.onViewUpdate() }
    }

    fun play() {
        exo.playWhenReady = true
        notifyEntryState(EntryStateKeys.PLAYING_STATE, PlayingState.PLAYING.name)
    }

    fun pause() {
        exo.playWhenReady = false
        notifyEntryState(EntryStateKeys.PLAYING_STATE, PlayingState.PAUSED.name)
    }

    private fun stopInternal() {
        managers.forEach { it.onDispose() }
        exo.stop()
        heartbeatJob?.cancel()
        heartbeatJob = null
        _spec.value = null
    }

    fun stop() {
        scope.launch {
            emitTeardown(PlayingState.STOPPED)
            stopInternal()
        }
    }

    fun clear() {
        if (!released.compareAndSet(false, true)) return
        stopInternal()
        exo.release()
    }

    private suspend fun emitTeardown(playingState: PlayingState) {
        _spec.value?.updateEntryState(EntryStateKeys.POSITION_MS, exo.currentPosition.toString())
        _spec.value?.updateEntryState(EntryStateKeys.PLAYING_STATE, playingState.name)
    }

    private fun currentPlayingState(): PlayingState =
        if (!exo.playWhenReady) PlayingState.PAUSED else PlayingState.PLAYING

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val interval = _spec.value?.progressIntervalMs?.takeIf { it > 0L } ?: DEFAULT_PROGRESS_INTERVAL_MS
                delay(interval)
                if (exo.playbackState != Player.STATE_READY) continue
                val activeSpec = _spec.value ?: continue
                val pos = exo.currentPosition
                activeSpec.updateEntryState(EntryStateKeys.POSITION_MS, pos.toString())
                activeSpec.updateEntryState(
                    EntryStateKeys.PLAYING_STATE,
                    currentPlayingState().name,
                )
            }
        }
    }
}
