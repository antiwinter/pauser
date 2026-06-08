package com.opentune.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.engine.OpenTuneExoPlayer
import com.opentune.player.engine.toMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val LOG_TAG = "OT_PlayerController"

@UnstableApi
class PlayerController(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _exo = OpenTuneExoPlayer.createForBundledSources(appContext).player

    val exoPlayer: ExoPlayer get() = _exo

    private val _currentSpec = MutableStateFlow<PlaybackSpec?>(null)
    val currentSpecFlow: StateFlow<PlaybackSpec?> = _currentSpec.asStateFlow()
    val currentSpec: PlaybackSpec? get() = _currentSpec.value

    private var _startMs: Long = 0L
    val startMs: Long get() = _startMs

    var storageCtx: PlaybackStorageContext? = null
        private set

    fun setStorageCtx(ctx: PlaybackStorageContext) {
        storageCtx = ctx
    }

    private var _nextVideoCallback: (() -> Unit)? = null
    private val _hasNextVideo = MutableStateFlow(false)
    val hasNextVideoFlow: StateFlow<Boolean> = _hasNextVideo.asStateFlow()

    fun setRequestNextVideoCallback(cb: (() -> Unit)?) {
        _nextVideoCallback = cb
        _hasNextVideo.value = cb != null
    }

    fun requestNextVideo() {
        _nextVideoCallback?.invoke()
    }

    fun setSpec(spec: PlaybackSpec, startMs: Long) {
        Log.d(LOG_TAG, "setSpec url=${spec.url} startMs=$startMs")
        _currentSpec.value = spec
        _startMs = startMs
        _exo.stop()
        _exo.setMediaSource(spec.toMediaSource(appContext))
        _exo.prepare()
    }

    fun play() {
        Log.d(LOG_TAG, "play")
        _exo.playWhenReady = true
    }

    fun pause() {
        Log.d(LOG_TAG, "pause")
        _exo.playWhenReady = false
    }

    fun stop() {
        Log.d(LOG_TAG, "stop")
        _exo.stop()
        _currentSpec.value = null
        storageCtx = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
    }

    fun release() {
        Log.d(LOG_TAG, "release: releasing ExoPlayer")
        _exo.release()
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }
}
