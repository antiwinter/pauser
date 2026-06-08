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
import com.opentune.player.PlaybackStorageContext
import com.opentune.player.PlayerController as BasePlayerController
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
) : AndroidViewModel(application) {

    val player = BasePlayerController(application)

    private var _lastItemRef: String? = null
    private var _lastClient: EndpointClient? = null
    private var _lastStartMs: Long = 0L

    private val _isShown = MutableStateFlow(false)
    val isShownFlow: StateFlow<Boolean> = _isShown.asStateFlow()

    private val _mediaCodecs = MutableStateFlow<List<MediaCodecInfo>>(emptyList())
    val mediaCodecs: StateFlow<List<MediaCodecInfo>> = _mediaCodecs

    private var _debounceJob: Job? = null

    // Delegate next-video callback to the base player so TvPlayerSurface can read it directly.
    fun setNextVideoCallback(cb: (() -> Unit)?) {
        player.setRequestNextVideoCallback(cb)
    }

    fun requestNextVideo() {
        player.requestNextVideo()
    }

    val isPrepared: Boolean get() = player.currentSpec != null
    val exoPlayer: ExoPlayer get() = player.exoPlayer
    val startMs: Long get() = player.startMs
    val currentItemRef: String? get() = _lastItemRef

    val bufferedDurationMs: Long
        get() {
            if (player.currentSpec == null) return 0L
            val pos = player.exoPlayer.currentPosition
            val buf = player.exoPlayer.bufferedPosition
            return maxOf(0L, buf - pos)
        }

    fun prepare(
        protocol: String,
        endpointId: String,
        itemRef: String,
        client: EndpointClient,
        startMs: Long = 0L,
        seriesStateKey: EntryStateKey? = null,
    ) {
        _lastItemRef = itemRef
        _lastClient = client
        _lastStartMs = startMs
        player.setStorageCtx(PlaybackStorageContext(
            entryStateStore = StorageBindingsHolder.get().entryStateStore,
            entryStateKey = EntryStateKey(protocol, endpointId, itemRef),
            seriesStateKey = seriesStateKey,
            appConfigStore = StorageBindingsHolder.get().appConfigStore,
        ))

        // Same item already prepared — just seek, no re-resolve needed.
        if (itemRef == player.currentSpec?.url && player.exoPlayer.playbackState != Player.STATE_IDLE) {
            player.exoPlayer.seekTo(startMs)
            Log.d(LOG_TAG, "prepare: same item, seekTo=$startMs")
            return
        }

        val hadPending = _debounceJob?.isActive == true
        _debounceJob?.cancel()
        _debounceJob = viewModelScope.launch {
            if (hadPending) delay(DEBOUNCE_MS)
            try {
                resolveAndSetSpec(itemRef, client, startMs)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(LOG_TAG, "prepare: failed", e)
            }
        }
    }

    /** Show the player surface immediately. Spinner shown until spec resolves. */
    fun play() {
        _isShown.value = true
        player.play()
        Log.d(LOG_TAG, "play: isShown=true")
    }

    fun pause() = player.pause()

    fun stop() {
        _debounceJob?.cancel()
        _debounceJob = null
        _isShown.value = false
        player.stop()
        _mediaCodecs.value = emptyList()
        Log.d(LOG_TAG, "stop")
    }

    fun release() = stop()

    private suspend fun resolveAndSetSpec(
        itemRef: String,
        client: EndpointClient,
        startMs: Long,
    ) {
        Log.d(LOG_TAG, "resolveAndSetSpec: itemRef=$itemRef startMs=$startMs")
        val spec = withContext(Dispatchers.IO) { client.getPlaybackSpec(itemRef, startMs) }
        _mediaCodecs.value = spec.mediaCodecs
        withContext(Dispatchers.Main) {
            player.setSpec(spec, startMs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        Log.d(LOG_TAG, "onCleared")
    }
}
