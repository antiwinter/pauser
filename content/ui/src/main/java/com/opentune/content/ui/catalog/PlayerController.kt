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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlayerController"
private const val DEBOUNCE_MS = 800L

/**
 * NavHost-scoped player controller. Owns a single ExoPlayer instance
 * shared between DetailScreen (embedded) and BrowseRoute (overlay).
 *
 * Callers invoke [setItem] and [play] freely — debounce, same-item
 * reuse, and prepare lifecycle are all managed internally.
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

    // Current resolved spec + ExoPlayer (StateFlow so Compose can observe)
    private var _currentSpec: com.opentune.player.PlaybackSpec? = null
    private val _exoPlayerFlow = MutableStateFlow<ExoPlayer?>(null)
    private var _startMs: Long = 0L
    private var _preparing = false

    // Debounce for setItem
    private var _debounceJob: Job? = null

    // Playback state tracking
    private var _listener: Player.Listener? = null
    private val _playbackState = MutableStateFlow<Int>(Player.STATE_IDLE)
    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())

    // Public state
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs.asStateFlow()
    val exoPlayerFlow: StateFlow<ExoPlayer?> = _exoPlayerFlow.asStateFlow()
    val isPrepared: Boolean get() = _exoPlayerFlow.value != null
    val exoPlayer: ExoPlayer? get() = _exoPlayerFlow.value
    val startMs: Long get() = _startMs

    /**
     * Set the item to play.
     *
     * - Cancels any pending debounce.
     * - If the same item is already prepared → seek immediately (no re-resolve).
     * - Otherwise → wait [DEBOUNCE_MS], then resolve PlaybackSpec and prepare ExoPlayer.
     *
     * Always prepares with playWhenReady=false. Callers invoke [play] when ready.
     */
    fun setItem(
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
    ) {
        _debounceJob?.cancel()
        _lastItemRef = itemRef
        _lastClient = client
        _lastStartMs = startMs

        // Same item already prepared — just seek, don't re-resolve.
        if (itemRef == _currentSpec?.url && _exoPlayerFlow.value != null) {
            _exoPlayerFlow.value?.seekTo(startMs)
            Log.d(LOG_TAG, "setItem: same item, seekTo=$startMs")
            return
        }

        _debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
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
     * - If the player is already prepared → playWhenReady=true.
     * - If still preparing → nothing to do, `prepare` will finish
     *   and `PlayerSurface` will auto-play via its LaunchedEffect.
     * - If not yet prepared (user pressed Play before debounce fired)
     *   → cancel debounce, force immediate prepare+play.
     *
     * The caller simply shows the overlay; loading spinner is shown
     * automatically while prepare completes.
     */
    fun play() {
        _debounceJob?.cancel()
        if (_preparing) {
            // prepare() is in-flight — just wait for it.
            Log.d(LOG_TAG, "play: prepare in-flight, waiting")
            return
        }
        if (_exoPlayerFlow.value != null) {
            // Already prepared — just start playback.
            val exo = _exoPlayerFlow.value!!
            if (!exo.playWhenReady) {
                exo.playWhenReady = true
                Log.d(LOG_TAG, "play: playWhenReady=true (prepared)")
            }
        } else {
            // Not yet prepared — resolve spec immediately and start playing.
            val itemRef = _lastItemRef ?: run {
                Log.w(LOG_TAG, "play: no item set")
                return
            }
            val client = _lastClient ?: run {
                Log.w(LOG_TAG, "play: no client set")
                return
            }
            val start = _lastStartMs
            _debounceJob = viewModelScope.launch {
                try {
                    prepare(itemRef, client, start)
                    _exoPlayerFlow.value?.playWhenReady = true
                    Log.d(LOG_TAG, "play: forced prepare+play")
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "play: forced prepare failed", e)
                }
            }
        }
    }

    /** Pause playback (playWhenReady = false). */
    fun pause() {
        _exoPlayerFlow.value?.let { exo ->
            if (exo.playWhenReady) {
                exo.playWhenReady = false
                Log.d(LOG_TAG, "pause: playWhenReady=false")
            }
        }
    }

    /** Seek to position. */
    fun seekTo(positionMs: Long) {
        _exoPlayerFlow.value?.seekTo(positionMs)
    }

    /** Get current position. */
    fun currentPosition(): Long = _exoPlayerFlow.value?.currentPosition ?: 0L

    /** Get duration. */
    fun duration(): Long = _exoPlayerFlow.value?.duration ?: 0L

    /** Check if currently playing. */
    fun isPlaying(): Boolean = _exoPlayerFlow.value?.isPlaying == true

    /** Check if buffering. */
    fun isBuffering(): Boolean = _exoPlayerFlow.value?.playbackState == Player.STATE_BUFFERING

    /** Release the player. */
    fun release() {
        _debounceJob?.cancel()
        _debounceJob = null
        releasePlayer()
    }

    /**
     * Dispose the current ExoPlayer instance without touching [_debounceJob].
     * Called from [prepare] so we don't self-cancel the running coroutine.
     */
    private fun releasePlayer() {
        val listener = _listener
        val exo = _exoPlayerFlow.value
        _listener = null

        if (exo != null) {
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    if (listener != null) {
                        exo.removeListener(listener)
                    }
                    exo.release()
                }
                Log.d(LOG_TAG, "release: ExoPlayer released")
            }
            _exoPlayerFlow.value = null
            _currentSpec = null
            _mediaCodecs.value = emptyList()
            _startMs = 0L
        }
    }

    /**
     * Resolve PlaybackSpec and prepare the ExoPlayer.
     * Sets playWhenReady = false so the player buffers but doesn't play.
     */
    private suspend fun prepare(
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
    ) {
        Log.d(LOG_TAG, "prepare: itemRef=$itemRef startMs=$startMs")
        _preparing = true
        try {
            releasePlayer() // clean up any previous player without cancelling ourselves

            val spec = withContext(Dispatchers.IO) {
                client.getPlaybackSpec(itemRef, startMs)
            }
            _currentSpec = spec
            _mediaCodecs.value = spec.mediaCodecs
            _startMs = startMs

            val playerWithMeter = withContext(Dispatchers.Main) {
                OpenTuneExoPlayer.createForBundledSources(appContext)
            }

            // Attach state listener
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _playbackState.value = state
                }
            }
            playerWithMeter.player.addListener(listener)
            _listener = listener

            // Prepare without playing — buffers ~5 minutes of data
            withContext(Dispatchers.Main) {
                val exo = playerWithMeter.player
                exo.stop()
                exo.setMediaSource(spec.toMediaSource(appContext))
                exo.prepare()
                Log.d(LOG_TAG, "prepare: ExoPlayer prepared, playWhenReady=false")
            }

            // Assign to StateFlow AFTER fully prepared so Compose sees a ready player
            _exoPlayerFlow.value = playerWithMeter.player
        } finally {
            _preparing = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }
}
