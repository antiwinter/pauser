package com.opentune.content.ui.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.content.contract.EndpointClient
import com.opentune.player.MediaCodecInfo
import com.opentune.player.engine.OpenTuneExoPlayer
import com.opentune.player.engine.toMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlayerController"
private const val DEBOUNCE_MS = 800L

/**
 * NavHost-scoped player controller. Owns a single long-lived ExoPlayer instance
 * shared between DetailScreen (embedded) and BrowseRoute (overlay).
 *
 * The player is created once and kept alive — only the media source is swapped
 * via [prepare]. Call [release] to stop playback and clear state (player stays
 * warm). The player is only fully released in [onCleared].
 *
 * Lifecycle:
 *   - Created when the NavHost is created
 *   - Survives configuration changes
 *   - Released when the NavHost is cleared
 */
@UnstableApi
class PlayerController(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    // Last item set by setItem — survives release so play() can re-prepare.
    private var _lastItemRef: String? = null
    private var _lastClient: EndpointClient? = null
    private var _lastStartMs: Long = 0L

    // Current resolved spec
    private var _currentSpec: com.opentune.player.PlaybackSpec? = null

    // The long-lived ExoPlayer instance.
    private val _player = OpenTuneExoPlayer.createForBundledSources(appContext).player

    // Playback listener for state tracking
    private var _listener: Player.Listener? = null

    // StateFlow observers
    private val _exoPlayerFlow = MutableStateFlow<ExoPlayer?>(null)
    private val _playbackState = MutableStateFlow<Int>(Player.STATE_IDLE)
    private val _playWhenReady = MutableStateFlow(false)
    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())
    private var _startMs: Long = 0L

    // Derived: non-null ExoPlayer only when actively playing/buffering (for overlay visibility)
    val exoPlayerFlow: StateFlow<ExoPlayer?> = _exoPlayerFlow.asStateFlow()
        .combine(_playWhenReady) { exo, pwr -> exo.takeIf { pwr } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Single job: either a debounce delay or an active prepare coroutine.
    private var _debounceJob: Job? = null

    init {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = state
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _playWhenReady.value = playWhenReady
            }
        }
        _player.addListener(listener)
        _listener = listener
    }

    // Public state
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs.asStateFlow()
    val isPrepared: Boolean get() = _currentSpec != null
    val exoPlayer: ExoPlayer? get() = _player
    val startMs: Long get() = _startMs

    /**
     * How much content is currently buffered ahead of the current position,
     * in milliseconds. Returns 0 when nothing is prepared.
     */
    val bufferedDurationMs: Long
        get() {
            val exo = _player
            if (_currentSpec == null) return 0L
            val pos = exo.currentPosition
            val buf = exo.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    /** Last item URL that was set (for debugging). */
    val currentItemRef: String? get() = _lastItemRef

    /**
     * Set the item to play.
     *
     * - First call → prepare immediately (no debounce).
     * - Subsequent call while one is pending → cancel, wait [DEBOUNCE_MS], then prepare.
     * - Same-item reuse → seek immediately.
     */
    fun setItem(
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
    ) {
        _lastItemRef = itemRef
        _lastClient = client
        _lastStartMs = startMs

        // Same item already prepared — just seek.
        if (itemRef == _currentSpec?.url && _player.playbackState != Player.STATE_IDLE) {
            _player.seekTo(startMs)
            Log.d(LOG_TAG, "setItem: same item, seekTo=$startMs")
            return
        }

        val hadPending = _debounceJob?.isActive == true
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            if (hadPending) delay(DEBOUNCE_MS)
            try {
                prepare(itemRef, client, startMs)
            } catch (_: CancellationException) {
                // expected when superseded
            } catch (e: Exception) {
                Log.e(LOG_TAG, "setItem: prepare failed", e)
            }
        }
    }

    /**
     * Start playback.
     *
     * - Cancel any pending debounced prepare.
     * - Same item already prepared → play.
     * - Item changed or not yet prepared → prepare then play.
     */
    fun play() {
        _debounceJob?.cancel()

        val itemRef = _lastItemRef
        val client = _lastClient
        if (itemRef != null && client != null && itemRef == _currentSpec?.url) {
            // Same item already prepared — just play.
            if (!_player.playWhenReady) {
                _player.playWhenReady = true
                Log.d(LOG_TAG, "play: playWhenReady=true (prepared)")
            }
            return
        }

        if (itemRef == null || client == null) {
            Log.w(LOG_TAG, "play: no item set")
            return
        }

        val start = _lastStartMs
        _debounceJob = viewModelScope.launch {
            try {
                prepare(itemRef, client, start)
                _player.playWhenReady = true
                Log.d(LOG_TAG, "play: prepare+play")
            } catch (_: CancellationException) {
                // expected when superseded
            } catch (e: Exception) {
                Log.e(LOG_TAG, "play: prepare+play failed", e)
            }
        }
    }

    /** Pause playback (playWhenReady = false). */
    fun pause() {
        if (_player.playWhenReady) {
            _player.playWhenReady = false
            Log.d(LOG_TAG, "pause: playWhenReady=false")
        }
    }

    /** Seek to position. */
    fun seekTo(positionMs: Long) {
        _player.seekTo(positionMs)
    }

    /** Get current position. */
    fun currentPosition(): Long = _player.currentPosition

    /** Get duration. */
    fun duration(): Long = _player.duration

    /** Check if currently playing. */
    fun isPlaying(): Boolean = _player.isPlaying

    /** Check if buffering. */
    fun isBuffering(): Boolean = _player.playbackState == Player.STATE_BUFFERING

    /**
     * Stop playback and clear current item state.
     * Player stays alive for reuse.
     */
    fun release() {
        _debounceJob?.cancel()
        _debounceJob = null
        _player.stop()
        _exoPlayerFlow.value = null
        _currentSpec = null
        _mediaCodecs.value = emptyList()
        _startMs = 0L
    }

    /**
     * Resolve PlaybackSpec and set it on the player.
     * Leaves playWhenReady unchanged.
     */
    private suspend fun prepare(
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
    ) {
        Log.d(LOG_TAG, "prepare: itemRef=$itemRef startMs=$startMs")

        val spec = withContext(Dispatchers.IO) {
            client.getPlaybackSpec(itemRef, startMs)
        }
        _currentSpec = spec
        _mediaCodecs.value = spec.mediaCodecs
        _startMs = startMs

        withContext(Dispatchers.Main) {
            _player.stop()
            _player.setMediaSource(spec.toMediaSource(appContext))
            _player.prepare()
            _exoPlayerFlow.value = _player
            Log.d(LOG_TAG, "prepare: player prepared")
        }
    }

    override fun onCleared() {
        super.onCleared()
        val listener = _listener
        if (listener != null) {
            _player.removeListener(listener)
        }
        _player.release()
        Log.d(LOG_TAG, "onCleared: ExoPlayer released")
    }
}
