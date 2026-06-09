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

    override val currentSpec: PlaybackSpec? get() = playbackSession.currentSpec
    override val storageCtx: PlaybackStorageContext? get() = playbackSession.storageCtx
    override val startMs: Long get() = playbackSession.startMs

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

    val isPrepared: Boolean get() = playbackSession.isPrepared
    val currentItemRef: String? get() = _pendingSpec?.itemRef ?: _lastResolvedItemRef
    val bufferedDurationMs: Long get() = playbackSession.bufferedDurationMs
    val loadedBytes: Long get() = playbackSession.loadedBytes

    private data class PendingSpec(
        val protocol: String,
        val endpointId: String,
        val itemRef: String,
        val client: EndpointClient,
        val startMs: Long,
        val seriesStateKey: EntryStateKey?,
    )

    private var _debounceJob: Job? = null
    private var _osdJob: Job? = null
    private var _pendingSpec: PendingSpec? = null
    private var _lastResolvedItemRef: String? = null

    fun prepare(
        protocol: String,
        endpointId: String,
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
        seriesStateKey: EntryStateKey? = null,
    ) {
        val hadPending = _debounceJob?.isActive == true
        _pendingSpec = PendingSpec(protocol, endpointId, itemRef, client, startMs, seriesStateKey)

        // Start pre-buffer OSD: show spec=0/1 immediately while debounce ticks.
        startPrebufferOsd(itemRef)

        launchResolve(withDelay = hadPending)
    }

    /** Show the player surface immediately. "Loading spec..." shown until spec resolves. */
    fun play() {
        _isShown.value = true
        playbackSession.play()
        _osdJob?.cancel()
        _osdJob = null
        // If a debounced resolve is still waiting, skip the delay and run it now.
        launchResolve()
        Log.d(LOG_TAG, "play: isShown=true")
    }

    fun pause() {
        playbackSession.pause()
    }

    fun hide() {
        _isShown.value = false
        playbackSession.pause()
        _osdJob?.cancel()
        _osdJob = null
        Log.d(LOG_TAG, "hide")
    }

    fun stop() {
        _debounceJob?.cancel()
        _debounceJob = null
        _osdJob?.cancel()
        _osdJob = null
        _pendingSpec = null
        _isShown.value = false
        playbackSession.stop()
        _lastResolvedItemRef = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
        _mediaCodecs.value = emptyList()
        Log.d(LOG_TAG, "stop")
    }

    private fun startPrebufferOsd(itemRef: String) {
        fun Long.toMinStr() = "%.1fmin".format(this / 60_000.0)
        fun Long.toMbStr() = "%.1fMB".format(this / (1024.0 * 1024.0))
        _osdJob?.cancel()
        _osdJob = viewModelScope.launch {
            while (true) {
                val specFlag = if (currentSpec != null) "1" else "0"
                gOSD.msg(
                    "$itemRef, spec=$specFlag/1, buffered=${bufferedDurationMs.toMinStr()}, " +
                        "bytes=${loadedBytes.toMbStr()}"
                )
                delay(1000)
            }
        }
    }

    private fun launchResolve(withDelay: Boolean = false) {
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            if (withDelay) delay(DEBOUNCE_MS)
            val params = _pendingSpec ?: return@launch
            _pendingSpec = null
            try {
                resolveAndPrepare(params)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(LOG_TAG, "launchResolve: failed", e)
            }
        }
    }

    private suspend fun resolveAndPrepare(params: PendingSpec) {
        val (protocol, endpointId, itemRef, client, startMs, seriesStateKey) = params
        val storageCtx = PlaybackStorageContext(
            entryStateStore = StorageBindingsHolder.get().entryStateStore,
            entryStateKey = EntryStateKey(protocol, endpointId, itemRef),
            seriesStateKey = seriesStateKey,
            appConfigStore = StorageBindingsHolder.get().appConfigStore,
        )

        Log.d(LOG_TAG, "resolveAndPrepare: itemRef=$itemRef startMs=$startMs")
        val spec = withContext(Dispatchers.IO) { client.getPlaybackSpec(itemRef, startMs) }
        _mediaCodecs.value = spec.mediaCodecs
        playbackSession.prepare(spec, storageCtx, startMs)
        _lastResolvedItemRef = itemRef
    }

    override fun onCleared() {
        super.onCleared()
        playbackSession.release()
        Log.d(LOG_TAG, "onCleared")
    }
}
