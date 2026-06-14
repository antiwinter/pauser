package com.opentune.content.ui.catalog.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.core.osd.gOSD
import com.opentune.player.MediaCodecInfo
import com.opentune.player.PlaybackDisplayInfo
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.engine.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlayerController"
private const val DEBOUNCE_MS = 800L

@UnstableApi
class PlayerController(
    application: Application,
) : AndroidViewModel(application), PlayerSurfaceController {
    override val playbackSession = PlaybackSession(application.applicationContext)

    private var _client: EndpointClient? = null

    private val _isShown = MutableStateFlow(false)
    val isShownFlow: StateFlow<Boolean> = _isShown.asStateFlow()

    /** Emits when [stop] is called (user closed the player). Not emitted for [reset]. */
    private val _stopEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopEvents: SharedFlow<Unit> = _stopEvents.asSharedFlow()

    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs

    private val _displayInfo = MutableStateFlow<PlaybackDisplayInfo>(PlaybackDisplayInfo())
    override val displayInfoFlow: StateFlow<PlaybackDisplayInfo> = _displayInfo.asStateFlow()

    private var _nextVideoCallback: (() -> Unit)? = null
    private val _hasNextVideo = MutableStateFlow(false)
    override val hasNextVideoFlow: StateFlow<Boolean> = _hasNextVideo.asStateFlow()

    private var _debounceJob: Job? = null
    private var _osdJob: Job? = null

    private var _pendingItemRef: String? = null
    private var _pendingStartMs: Long? = null

    private var _workingItemRef: String? = null
    private var _workingStartMs: Long? = null

    fun setNextVideoCallback(cb: (() -> Unit)?) {
        _nextVideoCallback = cb
        _hasNextVideo.value = cb != null
    }

    fun setDisplayInfo(info: EntryInfo) {
        Log.d(LOG_TAG, "setDisplayInfo: title=${info.title} bitrate=${info.bitrate}")
        _displayInfo.value = PlaybackDisplayInfo(info.title, info.bitrate)
    }

    override fun requestNextVideo() {
        _nextVideoCallback?.invoke()
    }

    fun setClient(client: EndpointClient) {
        _client = client
    }

    fun prepare(
        entryInfo: EntryInfo,
        startMs: Long? = null,
    ) {
        if (_client == null) {
            Log.w(LOG_TAG, "prepare: no client set, ignoring")
            return
        }
        _pendingItemRef = entryInfo.ref
        _pendingStartMs = startMs ?: entryInfo.userData?.positionMs ?: 0L
        Log.d(LOG_TAG, "prepare: ref=${entryInfo.ref} startMs=$_pendingStartMs (hadPending=${_debounceJob?.isActive})")

        setDisplayInfo(entryInfo)
        launchResolve(withDelay = _debounceJob?.isActive == true)
    }

    fun play() {
        _isShown.value = true
        launchResolve()
        playbackSession.play()
        Log.d(LOG_TAG, "play: isShown=true")
    }

    fun stop() {
        _isShown.value = false
        playbackSession.pause()
        _stopEvents.tryEmit(Unit)
        Log.d(LOG_TAG, "stop")
    }

    fun reset() {
        _isShown.value = false
        _debounceJob?.cancel()
        _osdJob?.cancel()
        playbackSession.stop()
        _pendingItemRef = null
        _pendingStartMs = null
        _workingItemRef = null
        _workingStartMs = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
        _mediaCodecs.value = emptyList()
        _displayInfo.value = PlaybackDisplayInfo()
        Log.d(LOG_TAG, "controller states cleared")
    }

    private fun startPrebufferOsd(itemRef: String) {
        fun Long.toMinStr() = "%.1fmin".format(this / 60_000.0)
        fun Long.toMbStr() = "%.1fMB".format(this / (1024.0 * 1024.0))
        _osdJob?.cancel()
        _osdJob = viewModelScope.launch {
            while (true) {
                gOSD.msg(
                    "$itemRef, spec=$_workingItemRef, " +
                        "buffered=${playbackSession.bufferedMs.toMinStr()}, " +
                        "bytes=${playbackSession.bufferedBytes.toMbStr()}",
                )
                delay(1000)
            }
        }
    }

    private fun launchResolve(withDelay: Boolean = false) {
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            try {
                if (withDelay) delay(DEBOUNCE_MS)
                if (_pendingItemRef == null) {
                    Log.w(LOG_TAG, "launchResolve: no pending item, ignoring")
                    return@launch
                }
                startPrebufferOsd(_pendingItemRef!!)
                resolveAndPrepare()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(LOG_TAG, "launchResolve: failed", e)
            }
        }
    }

    private suspend fun resolveAndPrepare() {
        val client = _client ?: return
        if (_pendingItemRef == _workingItemRef && _pendingStartMs == _workingStartMs) {
            Log.d(LOG_TAG, "launchResolve: pending spec matches working spec, ignoring")
            return
        }

        val itemRef = _pendingItemRef!!
        val startMs = _pendingStartMs ?: 0L

        Log.d(LOG_TAG, "resolveAndPrepare: itemRef=$itemRef startMs=$startMs")
        val spec = withContext(Dispatchers.IO) {
            client.getPlaybackSpec(itemRef, startMs)
        }
        _mediaCodecs.value = spec.mediaCodecs
        _workingItemRef = itemRef
        _workingStartMs = startMs

        playbackSession.prepare(spec)
    }

    override fun onCleared() {
        super.onCleared()
        playbackSession.clear()
        Log.d(LOG_TAG, "onCleared")
    }
}
