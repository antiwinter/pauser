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
import com.opentune.player.PlaybackState
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.engine.PlaybackSession
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.SubtitlePrefs
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlayerController"
private const val DEBOUNCE_MS = 800L

@UnstableApi
class PlayerController(
    application: Application,
) : AndroidViewModel(application), PlayerSurfaceController {
    override val playbackSession = PlaybackSession(application.applicationContext)

    private val entryStateStore: EntryStateStore = StorageBindingsHolder.get().entryStateStore
    private val appConfigStore = StorageBindingsHolder.get().appConfigStore

    // UI visibility of the player surface.
    private val _isShown = MutableStateFlow(false)
    val isShownFlow: StateFlow<Boolean> = _isShown.asStateFlow()

    /** Emits when [stop] is called (user closed the player). Not emitted for [reset]. */
    private val _stopEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopEvents: SharedFlow<Unit> = _stopEvents.asSharedFlow()

    // Codec info forwarded to detail screens for badge display.
    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs

    // Display info for player overlay (title, duration, bitrate) - set from EntryInfo.
    private val _displayInfo = MutableStateFlow<PlaybackDisplayInfo>(PlaybackDisplayInfo())
    override val displayInfoFlow: StateFlow<PlaybackDisplayInfo> = _displayInfo.asStateFlow()

    // Next-video callback — registered by SeriesDetailScreen; read by TvPlayerSurface.
    private var _nextVideoCallback: (() -> Unit)? = null
    private val _hasNextVideo = MutableStateFlow(false)
    override val hasNextVideoFlow: StateFlow<Boolean> = _hasNextVideo.asStateFlow()

    fun setNextVideoCallback(cb: (() -> Unit)?) {
        _nextVideoCallback = cb
        _hasNextVideo.value = cb != null
    }

    /** Set display info from EntryInfo for player overlay. */
    fun setDisplayInfo(info: EntryInfo) {
        Log.d(LOG_TAG, "setDisplayInfo: title=${info.title} bitrate=${info.bitrate}")
        _displayInfo.value = PlaybackDisplayInfo(info.title, info.bitrate)
    }

    override fun requestNextVideo() {
        _nextVideoCallback?.invoke()
    }

    // --- Context state (set at different frequencies) ---

    private var _client: EndpointClient? = null

    /** Episode/item key for the current prepare — set in resolveAndPrepare. */
    private var _currentEntryKey: EntryStateKey? = null

    /** Series-level state key — set when navigating into a series, cleared on stop. */
    private var _seriesStateKey: EntryStateKey? = null
    private var _parentStateKey: EntryStateKey? = null

    private var _debounceJob: Job? = null
    private var _osdJob: Job? = null

    private var _pendingItemRef: String? = null
    private var _pendingStartMs: Long? = null

    private var _workingItemRef: String? = null
    private var _workingStartMs: Long? = null

    init {
        startPersistenceCollectors()
    }

    /** Set the endpoint client. Call when the user switches endpoints. */
    fun setClient(client: EndpointClient) {
        _client = client
    }

    /**
     * Set series/parent context keys — call when entering a series or digipak, or clear on leaving.
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
     * Prepare playback for [entryInfo].
     * [startMs] defaults to entryInfo.userData?.positionMs, or 0L if not set.
     * Pass [startMs] explicitly to override (e.g., 0L for auto-advance).
     * [setClient] must have been called before this.
     */
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

    /** Show the player surface immediately. "Loading spec..." shown until spec resolves. */
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
        _currentEntryKey = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
        _mediaCodecs.value = emptyList()
        _displayInfo.value = PlaybackDisplayInfo()
        _seriesStateKey = null
        _parentStateKey = null
        Log.d(LOG_TAG, "controller states cleared")
    }

    private fun startPersistenceCollectors() {
        viewModelScope.launch {
            playbackSession.speedFlow.collect { speed ->
                _currentEntryKey?.let { saveSpeed(it, speed) }
            }
        }
        viewModelScope.launch {
            playbackSession.subtitleTrackIdFlow.collect { trackId ->
                _currentEntryKey?.let { saveSubtitleTrack(it, trackId) }
            }
        }
        viewModelScope.launch {
            playbackSession.audioTrackIdFlow.collect { trackId ->
                _currentEntryKey?.let { saveAudioTrack(it, trackId) }
            }
        }
        viewModelScope.launch {
            combine(
                playbackSession.subtitleOffsetFractionFlow,
                playbackSession.subtitleSizeScaleFlow,
            ) { offset, scale -> SubtitlePrefs(offset, scale) }
                .collect { prefs ->
                    withContext(Dispatchers.IO) {
                        appConfigStore.saveSubtitlePrefs(prefs)
                    }
                }
        }
    }

    private suspend fun saveSpeed(entryKey: EntryStateKey, speed: Float) {
        withContext(Dispatchers.IO) {
            entryStateStore.upsertSpeed(entryKey, speed)
            _seriesStateKey?.let { entryStateStore.upsertSpeed(it, speed) }
            _parentStateKey?.let { entryStateStore.upsertSpeed(it, speed) }
        }
    }

    private suspend fun saveSubtitleTrack(entryKey: EntryStateKey, trackId: String?) {
        withContext(Dispatchers.IO) {
            entryStateStore.upsertSubtitleTrack(entryKey, trackId)
            _seriesStateKey?.let { entryStateStore.upsertSubtitleTrack(it, trackId) }
            _parentStateKey?.let { entryStateStore.upsertSubtitleTrack(it, trackId) }
        }
    }

    private suspend fun saveAudioTrack(entryKey: EntryStateKey, trackId: String?) {
        withContext(Dispatchers.IO) {
            entryStateStore.upsertAudioTrack(entryKey, trackId)
            _seriesStateKey?.let { entryStateStore.upsertAudioTrack(it, trackId) }
            _parentStateKey?.let { entryStateStore.upsertAudioTrack(it, trackId) }
        }
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
            && _pendingStartMs == _workingStartMs
        ) {
            Log.d(LOG_TAG, "launchResolve: pending spec matches working spec, ignoring")
            return
        }

        val itemRef = _pendingItemRef!!
        val startMs = _pendingStartMs ?: 0L
        val entryKey = EntryStateKey(client.endpointId, itemRef)
        _currentEntryKey = entryKey

        Log.d(LOG_TAG, "resolveAndPrepare: itemRef=$itemRef startMs=$startMs")
        val (spec, row, subtitlePrefs) = withContext(Dispatchers.IO) {
            Triple(
                client.getPlaybackSpec(itemRef, startMs),
                entryStateStore.get(entryKey),
                appConfigStore.loadSubtitlePrefs(),
            )
        }
        _mediaCodecs.value = spec.mediaCodecs
        _workingItemRef = itemRef
        _workingStartMs = startMs

        val seed = PlaybackState(
            positionMs = startMs,
            speed = row?.playbackSpeed ?: 1f,
            subtitleTrackId = row?.selectedSubtitleTrackId,
            audioTrackId = row?.selectedAudioTrackId,
            subtitleOffsetFraction = subtitlePrefs.offsetFraction,
            subtitleSizeScale = subtitlePrefs.sizeScale,
        )
        playbackSession.prepare(spec, seed)
    }

    override fun onCleared() {
        super.onCleared()
        playbackSession.clear()
        Log.d(LOG_TAG, "onCleared")
    }
}
