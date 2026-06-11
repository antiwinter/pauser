package com.opentune.content.ui.catalog.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.opentune.content.contract.EndpointClient
import com.opentune.core.osd.gOSD
import com.opentune.player.MediaCodecInfo
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackStorageContext
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.engine.PlaybackSession
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
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

@UnstableApi
class PlayerController(
    application: Application,
) : AndroidViewModel(application), PlayerSurfaceController {
    override val playbackSession = PlaybackSession(application.applicationContext)

    // UI visibility of the player surface.
    private val _isShown = MutableStateFlow(false)
    val isShownFlow: StateFlow<Boolean> = _isShown.asStateFlow()

    // Codec info forwarded to detail screens for badge display.
    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs

    // Next-video callback — registered by SeriesDetailRoute; read by TvPlayerSurface.
    private var _nextVideoCallback: (() -> Unit)? = null
    private val _hasNextVideo = MutableStateFlow(false)
    override val hasNextVideoFlow: StateFlow<Boolean> = _hasNextVideo.asStateFlow()

    fun setNextVideoCallback(cb: (() -> Unit)?) {
        _nextVideoCallback = cb
        _hasNextVideo.value = cb != null
    }

    override fun requestNextVideo() {
        _nextVideoCallback?.invoke()
    }

    // --- Context state (set at different frequencies) ---

    private var _client: EndpointClient? = null

    /** Series-level state key — set when navigating into a series, cleared on stop. */
    private var _seriesStateKey: EntryStateKey? = null
    private var _parentStateKey: EntryStateKey? = null

    private var _debounceJob: Job? = null
    private var _osdJob: Job? = null
    
    private var _pendingItemRef: String? = null
    private var _pendingStartMs: Long? = null


    private var _workingItemRef: String? = null
    private var _workingStartMs: Long? = null

    /** Set the endpoint client. Call when the user switches endpoints. */
    fun setClient(client: EndpointClient) {
        _client = client
    }

    /**
     * Set series/parent context keys — call when entering a series, or clear on leaving.
     * These have a longer lifetime than individual item prepares.
     */
    fun setContext(
        seriesStateKey: EntryStateKey? = null,
        parentStateKey: EntryStateKey? = null,
    ) {
        _seriesStateKey = seriesStateKey
        _parentStateKey = parentStateKey
    }

    /**
     * Prepare playback for [itemRef] starting at [startMs].
     * [setClient] must have been called before this.
     */
    fun prepare(
        itemRef: String,
        startMs: Long = 0L,
    ) {
        val client = _client ?: run {
            Log.w(LOG_TAG, "prepare: no client set, ignoring")
            return
        }
        Log.d(LOG_TAG, "prepare: ref=$itemRef startMs=$startMs (hadPending=${_debounceJob?.isActive})")
        _pendingItemRef = itemRef
        _pendingStartMs = startMs

        launchResolve(withDelay = _debounceJob?.isActive == true)
    }

    /** Show the player surface immediately. "Loading spec..." shown until spec resolves. */
    fun play() {
        _isShown.value = true
        // kick off awaiting jobs
        launchResolve()
        playbackSession.play()
        Log.d(LOG_TAG, "play: isShown=true")
    }

    fun stop() {
        _isShown.value = false
        playbackSession.pause()
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
        _seriesStateKey = null
        _parentStateKey = null
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
                    "bytes=${playbackSession.bufferedBytes.toMbStr()}"
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
                val itemRef = _pendingItemRef ?: run {
                    Log.w(LOG_TAG, "launchResolve: no pending item, ignoring")
                    return@launch
                }
                startPrebufferOsd(itemRef)
                resolveAndPrepare()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(LOG_TAG, "launchResolve: failed", e)
            }
        }
    }

    private suspend fun resolveAndPrepare() {
        val client = _client ?: return
        if (_pendingItemRef == _workingItemRef
            && _pendingStartMs == _workingStartMs) {
            Log.d(LOG_TAG, "launchResolve: pending spec matches working spec, ignoring")
            return
        }

        val storageCtx = PlaybackStorageContext(
            entryStateStore = StorageBindingsHolder.get().entryStateStore,
            entryStateKey = EntryStateKey(client.endpointId, _pendingItemRef!!),
            seriesStateKey = _seriesStateKey,
            parentStateKey = _parentStateKey,
            appConfigStore = StorageBindingsHolder.get().appConfigStore,
        )

        Log.d(LOG_TAG, "resolveAndPrepare: itemRef=$_pendingItemRef startMs=$_pendingStartMs")
        val spec = withContext(Dispatchers.IO) {
            client.getPlaybackSpec(_pendingItemRef!!, _pendingStartMs ?: 0L)
        }
        _mediaCodecs.value = spec.mediaCodecs
        _workingItemRef = _pendingItemRef
        _workingStartMs = _pendingStartMs

        playbackSession.prepare(spec, storageCtx, _workingStartMs ?: 0L)
    }

    override fun onCleared() {
        super.onCleared()
        playbackSession.clear()
        Log.d(LOG_TAG, "onCleared")
    }
}
