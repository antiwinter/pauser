package com.opentune.player.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.opentune.player.EntryStateKeys
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlayingState
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

    // Track/decoder info, owned here so its listeners live for the whole ExoPlayer lifetime and
    // never miss the one-shot decoder-init callbacks. Reset on prepare(). See TrackInfo.kt.
    private val _trackInfo = MutableStateFlow(TrackInfo())
    internal val trackInfoFlow: StateFlow<TrackInfo> = _trackInfo.asStateFlow()

    init {
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val videoFormat = tracks.selectedFormat(C.TRACK_TYPE_VIDEO)
                val audioFormat = tracks.selectedFormat(C.TRACK_TYPE_AUDIO)
                _trackInfo.value = _trackInfo.value.copy(
                    videoMime = videoFormat?.sampleMimeType,
                    audioMime = audioFormat?.sampleMimeType,
                    isHdrCapable = isHdrFormat(videoFormat),
                    videoBitrate = formatBitrate(videoFormat),
                )
                Log.d(
                    SESSION_LOG,
                    "tracks v=${videoFormat?.sampleMimeType} a=${audioFormat?.sampleMimeType} " +
                        "hdr=${isHdrFormat(videoFormat)} bitrate=${formatBitrate(videoFormat)} groups=${tracks.groups.size}"
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.causeChainContains("MediaCodecVideoRenderer") ->
                        _trackInfo.value = _trackInfo.value.copy(videoDecoderStatus = "err")
                    error.causeChainContains("MediaCodecAudioRenderer", "AudioSink") ->
                        _trackInfo.value = _trackInfo.value.copy(audioDecoderStatus = "err")
                }
            }
        })

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.d(SESSION_LOG, "videoDecoder=$decoderName")
                _trackInfo.value = _trackInfo.value.copy(videoDecoderStatus = simplifyDecoderName(decoderName))
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.d(SESSION_LOG, "audioDecoder=$decoderName")
                _trackInfo.value = _trackInfo.value.copy(audioDecoderStatus = simplifyDecoderName(decoderName))
            }

            // Fires for both decoded and passthrough audio. Passthrough never gets a decoder-init,
            // so claim the field only while still unresolved ("n/a"); a real decoder-init wins either
            // way (it overrides "passthrough", or this no-ops when it already ran).
            override fun onAudioEnabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                if (_trackInfo.value.audioDecoderStatus == "n/a") {
                    _trackInfo.value = _trackInfo.value.copy(audioDecoderStatus = "passthrough")
                }
            }
        })
    }


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

    /**
     * The sidecar config for the currently-selected external subtitle, or null. Derived from the
     * saved track id (kept in [subtitleTrackIdFlow]) so any rebuild — decoder fallback, sidecar
     * recovery — can reattach the active sidecar through the unified [toMediaSource] builder.
     */
    fun activeSidecarSubtitle(): MediaItem.SubtitleConfiguration? {
        val spec = _spec.value ?: return null
        return spec.savedSubtitleTrack(_subtitleTrackId.value)?.toSidecarConfig()
    }

    fun updateSpeed(speed: Float) {
        _speed.value = speed
        notifyEntryState(EntryStateKeys.SPEED, speed.toString())
    }

    fun updateSubtitleTrackId(trackId: String?) {
        _subtitleTrackId.value = trackId
        notifyEntryState(EntryStateKeys.SUBTITLE_TRACK_ID, trackId)
    }

    fun updateAudioTrackId(trackId: String?) {
        _audioTrackId.value = trackId
        notifyEntryState(EntryStateKeys.AUDIO_TRACK_ID, trackId)
    }

    fun updateSubtitlePrefs(offsetFraction: Float, sizeScale: Float) {
        _subtitleOffsetFraction.value = offsetFraction
        _subtitleSizeScale.value = sizeScale
        scope.launch {
            val spec = _spec.value ?: return@launch
            spec.updateEntryState(EntryStateKeys.SUBTITLE_OFFSET_FRACTION, offsetFraction.toString())
            spec.updateEntryState(EntryStateKeys.SUBTITLE_SIZE_SCALE, sizeScale.toString())
        }
    }

    fun notifyEntryState(key: String, value: String?) {
        scope.launch {
            _spec.value?.updateEntryState(key, value)
        }
    }

    suspend fun prepare(spec: PlaybackSpec) {
        _spec.value?.let { emitTeardown(it, PlayingState.STOPPED) }

        val seed = spec.state
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

            _trackInfo.value = TrackInfo()
            exo.stop()
            exo.playWhenReady = false
            exo.playbackParameters = PlaybackParameters(savedSpeed)

            // Restore the saved subtitle through the same unified builder used everywhere else, so a
            // first launch actually applies it instead of only showing it selected in the menu.
            // External/bitmap track → build the source WITH the sidecar; embedded → select by
            // preferred language once tracks load; none → disable text (the prior behaviour).
            val savedTrack = spec.savedSubtitleTrack(seed.subtitleTrackId)
            val sidecar = savedTrack?.toSidecarConfig()
            val textParams = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            when {
                sidecar != null -> textParams
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setSelectUndeterminedTextLanguage(true)
                savedTrack != null -> {
                    textParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setSelectUndeterminedTextLanguage(true)
                    savedTrack.language?.let { textParams.setPreferredTextLanguage(it) }
                }
                else -> textParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            exo.trackSelectionParameters = textParams.build()
            exo.setMediaSource(spec.toMediaSource(appContext, sidecar), seed.positionMs)
            exo.prepare()
        }
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
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
        heartbeatJob?.cancel()
        heartbeatJob = null
        exo.stop()
    }

    fun stop() {
        val spec = _spec.value
        stopInternal()
        _spec.value = null
        if (spec != null) {
            scope.launch { emitTeardown(spec, PlayingState.STOPPED) }
        }
    }

    fun clear() {
        stopInternal()
        _spec.value = null
        exo.release()
    }

    private suspend fun emitTeardown(spec: PlaybackSpec, playingState: PlayingState) {
        val pos = exo.currentPosition
        spec.updateEntryState(EntryStateKeys.POSITION_MS, pos.toString())
        spec.updateEntryState(EntryStateKeys.PLAYING_STATE, playingState.name)
    }

    private fun currentPlayingState(): PlayingState =
        if (!exo.playWhenReady) PlayingState.PAUSED else PlayingState.PLAYING

    private fun startHeartbeat(spec: PlaybackSpec) {
        heartbeatJob?.cancel()
        val interval = spec.progressIntervalMs.takeIf { it > 0L } ?: DEFAULT_PROGRESS_INTERVAL_MS
        if (interval <= 0L) return
        heartbeatJob = scope.launch {
            while (isActive) {
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
