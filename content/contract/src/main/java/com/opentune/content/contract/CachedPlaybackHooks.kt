package com.opentune.content.contract

import com.opentune.player.OpenTunePlaybackHooks
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps provider playback hooks: persists position to Room + in-memory cache, then delegates
 * to the inner hooks (e.g. JS remote sync, SMB token lifecycle).
 */
internal class CachedPlaybackHooks(
    private val inner: OpenTunePlaybackHooks,
    private val entryStateKey: EntryStateKey,
    private val store: EntryStateStore,
    private val endpointId: String,
    private val itemRef: String,
) : OpenTunePlaybackHooks {

    override fun progressIntervalMs(): Long = inner.progressIntervalMs()

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) {
        persistPosition(positionMs)
        inner.onPlaybackReady(positionMs, playbackRate)
    }

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float, isPaused: Boolean) {
        if (!isPaused) {
            persistPosition(positionMs)
        }
        inner.onProgressTick(positionMs, playbackRate, isPaused)
    }

    override suspend fun onStop(positionMs: Long) {
        persistPosition(positionMs)
        inner.onStop(positionMs)
    }

    override fun onDispose() {
        inner.onDispose()
    }

    private suspend fun persistPosition(positionMs: Long) {
        withContext(Dispatchers.IO) {
            store.upsertPosition(entryStateKey, positionMs)
            EndpointCache.patchEntryUserData(endpointId, itemRef, positionMs)
        }
    }
}
