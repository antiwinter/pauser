package com.insomnia.content.ui.catalog.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.insomnia.content.epcache.CachingEndpointClient
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EntryInfo
import com.insomnia.core.osd.gOSD
import com.insomnia.player.MediaCodecInfo
import com.insomnia.player.PlaybackDisplayInfo
import com.insomnia.player.PlaybackSpec
import com.insomnia.player.PlayerSurfaceController
import com.insomnia.player.engine.PlaybackSession
import com.insomnia.player.manager.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

private const val DEBOUNCE_MS = 800L

@UnstableApi
class PlayerController(
    application: Application,
) : AndroidViewModel(application), PlayerSurfaceController {
    override val playbackSession = PlaybackSession(application.applicationContext)

    private var _client: CachingEndpointClient? = null

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

    private var _pendingInfo: EntryInfo? = null
    private var _pendingStartMs: Long? = null

    private var _workingItemRef: String? = null
    private var _workingStartMs: Long? = null

    private val _sourceManager = MutableStateFlow<SourceManager?>(null)
    override val sourceManagerFlow: StateFlow<SourceManager?> = _sourceManager.asStateFlow()

    fun setNextVideoCallback(cb: (() -> Unit)?) {
        _nextVideoCallback = cb
        _hasNextVideo.value = cb != null
    }

    fun setDisplayInfo(info: EntryInfo) {
        Timber.d( "setDisplayInfo: title=${info.title}")
        _displayInfo.value = PlaybackDisplayInfo(info.title)
    }

    override fun requestNextVideo() {
        _nextVideoCallback?.invoke()
    }

    fun setClient(client: EndpointClient) {
        _client = client as CachingEndpointClient
    }

    fun prepare(
        entryInfo: EntryInfo,
        startMs: Long? = null,
    ) {
        if (_client == null) {
            Timber.w( "prepare: no client set, ignoring")
            return
        }
        val resumeMs = entryInfo.userData?.positionMs ?: 0L
        _pendingStartMs = (startMs ?: resumeMs).coerceAtLeast(0L)
        _pendingInfo = entryInfo
        Timber.d( "prepare: ref=${entryInfo.ref} resumeMs=$resumeMs startMs=$_pendingStartMs (hadPending=${_debounceJob?.isActive})")

        setDisplayInfo(entryInfo)
        launchResolve(withDelay = _debounceJob?.isActive == true)
    }

    fun play() {
        _isShown.value = true
        launchResolve(onComplete = {
            playbackSession.play()
            _osdJob?.cancel()
        })
        Timber.d( "play: isShown=true")
    }

    fun stop() {
        _isShown.value = false
        playbackSession.pause()
        _stopEvents.tryEmit(Unit)
        Timber.d( "stop")
    }

    fun reset() {
        _isShown.value = false
        _debounceJob?.cancel()
        _osdJob?.cancel()
        playbackSession.stop()
        _pendingStartMs = null
        _pendingInfo = null
        _workingItemRef = null
        _workingStartMs = null
        _sourceManager.value = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
        _mediaCodecs.value = emptyList()
        _displayInfo.value = PlaybackDisplayInfo()
        Timber.d( "controller states cleared")
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

    private fun launchResolve(withDelay: Boolean = false, onComplete: (() -> Unit)? = null) {
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            try {
                if (withDelay) delay(DEBOUNCE_MS)
                if (_pendingInfo == null) {
                    Timber.w( "launchResolve: no pending item, ignoring")
                    return@launch
                }
                startPrebufferOsd(_pendingInfo!!.ref)
                resolveAndPrepare()
                onComplete?.invoke()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Timber.e(e, "launchResolve: failed")
                _osdJob?.cancel()
                gOSD.msg("resolve playback failed: ${e.message ?: "unknown"}")
            }
        }
    }

    private suspend fun resolveAndPrepare() {
        val client = _client ?: return
        val info = _pendingInfo ?: return
        if (info.ref == _workingItemRef && _pendingStartMs == _workingStartMs) {
            Timber.d( "launchResolve: pending spec matches working spec, ignoring")
            return
        }

        val itemRef = info.ref
        val startMs = _pendingStartMs ?: 0L

        Timber.d( "resolveAndPrepare: itemRef=$itemRef startMs=$startMs")
        val spec = client.getPlaybackSpec(info, startMs)

        _sourceManager.value = SourceManager(spec).also { mgr ->
            mgr.onSourceSelected = { s -> viewModelScope.launch { playbackSession.prepare(s) } }
        }
        _mediaCodecs.value = spec.sources[spec.state.sourceIndex].mediaCodecs
        _workingItemRef = itemRef
        _workingStartMs = startMs

        playbackSession.prepare(spec)
    }

    override fun onCleared() {
        super.onCleared()
        playbackSession.clear()
        Timber.d( "onCleared")
    }
}
