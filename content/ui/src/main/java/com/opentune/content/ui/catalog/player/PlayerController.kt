package com.opentune.content.ui.catalog.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.content.contract.EndpointClient
import com.opentune.player.MediaCodecInfo
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackStorageContext
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.engine.OpenTuneExoPlayer
import com.opentune.player.engine.toMediaSource
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

    private val appContext = application.applicationContext

    // ExoPlayer — constructed once, lives for the ViewModel lifetime.
    override val exoPlayer: ExoPlayer = OpenTuneExoPlayer.createForBundledSources(appContext).player

    // Current spec and start position — read by TvPlayerSurface.
    private val _currentSpec = MutableStateFlow<PlaybackSpec?>(null)
    val currentSpecFlow: StateFlow<PlaybackSpec?> = _currentSpec.asStateFlow()
    override val currentSpec: PlaybackSpec? get() = _currentSpec.value

    override var startMs: Long
        get() = _startMs
        private set(value) { _startMs = value }
    private var _startMs: Long = 0L

    // Storage context for progress/subtitle/audio persistence — set before play.
    override var storageCtx: PlaybackStorageContext? = null
        private set

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

    val isPrepared: Boolean get() = currentSpec != null
    val currentItemRef: String? get() = _pendingSpec?.itemRef ?: _lastResolvedItemRef

    val bufferedDurationMs: Long
        get() {
            if (currentSpec == null) return 0L
            val pos = exoPlayer.currentPosition
            val buf = exoPlayer.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    private data class PendingSpec(val itemRef: String, val client: EndpointClient, val startMs: Long)

    private var _debounceJob: Job? = null
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
        storageCtx = PlaybackStorageContext(
            entryStateStore = StorageBindingsHolder.get().entryStateStore,
            entryStateKey = EntryStateKey(protocol, endpointId, itemRef),
            seriesStateKey = seriesStateKey,
            appConfigStore = StorageBindingsHolder.get().appConfigStore,
        )

        // Same item already buffered — just seek, no re-resolve needed.
        if (itemRef == currentSpec?.url && exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.seekTo(startMs)
            Log.d(LOG_TAG, "prepare: same item, seekTo=$startMs")
            return
        }

        val hadPending = _debounceJob?.isActive == true
        _pendingSpec = PendingSpec(itemRef, client, startMs)
        launchResolve(withDelay = hadPending)
    }

    /** Show the player surface immediately. Spinner shown until spec resolves. */
    fun play() {
        _isShown.value = true
        exoPlayer.playWhenReady = true
        // If a debounced resolve is still waiting, skip the delay and run it now.
        launchResolve()
        Log.d(LOG_TAG, "play: isShown=true")
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    fun stop() {
        _debounceJob?.cancel()
        _debounceJob = null
        _pendingSpec = null
        _isShown.value = false
        exoPlayer.stop()
        _currentSpec.value = null
        _lastResolvedItemRef = null
        storageCtx = null
        _nextVideoCallback = null
        _hasNextVideo.value = false
        _mediaCodecs.value = emptyList()
        Log.d(LOG_TAG, "stop")
    }

    private fun launchResolve(withDelay: Boolean = false) {
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            if (withDelay) delay(DEBOUNCE_MS)
            val params = _pendingSpec ?: return@launch
            _pendingSpec = null
            try {
                resolveAndSetSpec(params)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(LOG_TAG, "launchResolve: failed", e)
            }
        }
    }

    private suspend fun resolveAndSetSpec(params: PendingSpec) {
        val (itemRef, client, startMs) = params
        if (itemRef == _lastResolvedItemRef && currentSpec != null && exoPlayer.playbackState != Player.STATE_IDLE) {
            Log.d(LOG_TAG, "resolveAndSetSpec: already playing itemRef=$itemRef, skipping")
            return
        }
        Log.d(LOG_TAG, "resolveAndSetSpec: itemRef=$itemRef startMs=$startMs")
        val spec = withContext(Dispatchers.IO) { client.getPlaybackSpec(itemRef, startMs) }
        _mediaCodecs.value = spec.mediaCodecs
        withContext(Dispatchers.Main) {
            _currentSpec.value = spec
            _startMs = startMs
            _lastResolvedItemRef = itemRef
            exoPlayer.stop()
            exoPlayer.setMediaSource(spec.toMediaSource(appContext))
            exoPlayer.prepare()
        }
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer.release()
        Log.d(LOG_TAG, "onCleared")
    }
}
