package com.opentune.content.ui.catalog

import android.app.Application
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.content.contract.EndpointClient
import com.opentune.player.PlaybackSpec
import com.opentune.player.engine.OpenTuneExoPlayer
import com.opentune.player.engine.toMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlayerController"

/**
 * NavHost-scoped player controller. Owns a single ExoPlayer instance
 * shared between DetailScreen (embedded) and PlayerRoute (standalone).
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

    // Current playback context
    private var _currentSpec: PlaybackSpec? = null
    private var _exoPlayer: MutableState<ExoPlayer?> = mutableStateOf(null)
    private var _startMs: Long = 0L

    // State
    val isPrepared: Boolean get() = _exoPlayer.value != null
    val exoPlayer: ExoPlayer? get() = _exoPlayer.value
    val startMs: Long get() = _startMs

    /**
     * Resolve PlaybackSpec and prepare the ExoPlayer.
     * Sets playWhenReady = false so the player buffers but doesn't play.
     */
    suspend fun prepare(
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
    ) {
        Log.d(LOG_TAG, "prepare: itemRef=$itemRef startMs=$startMs")
        release() // clean up any previous player

        val spec = withContext(Dispatchers.IO) {
            client.getPlaybackSpec(itemRef, startMs)
        }
        _currentSpec = spec
        _startMs = startMs

        val playerWithMeter = withContext(Dispatchers.Main) {
            OpenTuneExoPlayer.createForBundledSources(appContext)
        }
        _exoPlayer.value = playerWithMeter.player

        // Prepare without playing — buffers ~5 minutes of data
        withContext(Dispatchers.Main) {
            val exo = _exoPlayer.value!!
            exo.stop()
            exo.setMediaSource(spec.toMediaSource(appContext))
            exo.prepare()
            Log.d(LOG_TAG, "prepare: ExoPlayer prepared, playWhenReady=false")
        }
    }

    /** Start playback (playWhenReady = true). */
    fun play() {
        _exoPlayer.value?.let { exo ->
            if (!exo.playWhenReady) {
                exo.playWhenReady = true
                Log.d(LOG_TAG, "play: playWhenReady=true")
            }
        }
    }

    /** Pause playback (playWhenReady = false). */
    fun pause() {
        _exoPlayer.value?.let { exo ->
            if (exo.playWhenReady) {
                exo.playWhenReady = false
                Log.d(LOG_TAG, "pause: playWhenReady=false")
            }
        }
    }

    /** Seek to position. */
    fun seekTo(positionMs: Long) {
        _exoPlayer.value?.seekTo(positionMs)
    }

    /** Get current position. */
    fun currentPosition(): Long = _exoPlayer.value?.currentPosition ?: 0L

    /** Get duration. */
    fun duration(): Long = _exoPlayer.value?.duration ?: 0L

    /** Check if currently playing. */
    fun isPlaying(): Boolean = _exoPlayer.value?.isPlaying == true

    /** Check if buffering. */
    fun isBuffering(): Boolean = _exoPlayer.value?.playbackState == androidx.media3.common.Player.STATE_BUFFERING

    /** Release the player. */
    fun release() {
        val exo = _exoPlayer.value
        if (exo != null) {
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    exo.release()
                }
                Log.d(LOG_TAG, "release: ExoPlayer released")
            }
            _exoPlayer.value = null
            _currentSpec = null
            _startMs = 0L
        }
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }
}
