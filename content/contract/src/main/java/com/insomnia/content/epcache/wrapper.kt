package com.insomnia.content.epcache

import com.insomnia.content.contract.EntryEmission
import com.insomnia.content.contract.EntryEmitter
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryList
import com.insomnia.content.contract.EntryTag
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointValidationResult
import com.insomnia.content.contract.FileRelay
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SERVER_PORT
import com.insomnia.content.contract.SortField
import com.insomnia.content.contract.SortOrder
import com.insomnia.content.contract.UserDataMerge
import com.insomnia.core.form.contract.QrResult
import com.insomnia.player.EntryStateKeys
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlayingState
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.storage.EntryStateKey
import com.insomnia.storage.EntryStateStore
import com.insomnia.storage.StorageBindingsHolder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.ImageLoader

/**
 * Wrapper around an [EndpointClient] that adds caching and progressive entry emission.
 *
 * Cached methods (listEntry, getEntries, getTaggedEntries): serve from cache when fresh,
 * otherwise fetch from delegate. Results are exposed as [Flow] of [EntryEmission] so consumers
 * can display data as it arrives.
 *
 * Non-cached methods (test, getPlaybackSources, openStream, getQr, pollQr): always delegate.
 *
 * Progressive emission: providers can call [EndpointClient.entryEmitter] during execution to
 * report partial results. The wrapper collects these into the Flow. If the provider doesn't
 * emit, the final return value is emitted as the single complete result.
 */
class CachingEndpointClient(
    private val delegate: EndpointClient,
    private val useGenart: Boolean,
) {

    private val inheritableKeys = setOf(
        EntryStateKeys.SPEED,
        EntryStateKeys.SUBTITLE_TRACK_ID,
        EntryStateKeys.AUDIO_TRACK_ID,
    )

    private val activeStreamRefs = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    val endpointId: String get() = delegate.endpointId
    val protocol: String get() = delegate.protocol
    val displayName: String get() = delegate.displayName
    val imageLoader: ImageLoader? get() = delegate.imageLoader
    val proxyClient: ProxyClient get() = delegate.proxyClient
    val progressIntervalMs: Long get() = delegate.progressIntervalMs

    // --- Non-cached delegates ---

    suspend fun test(): EndpointValidationResult = delegate.test()

    suspend fun getPlaybackSources(itemRef: String, startMs: Long = 0L): List<PlaybackSource> =
        delegate.getPlaybackSources(itemRef, startMs)

    suspend fun updateEntryState(itemRef: String, key: String, value: String?) {
        withContext(Dispatchers.IO) {
            persistLocalEntryState(itemRef, key, value)
        }
        delegate.updateEntryState(itemRef, key, value)
    }

    suspend fun openStream(itemRef: String) = delegate.openStream(itemRef)
    suspend fun getQr(): QrResult.QrReady? = delegate.getQr()
    suspend fun pollQr(token: String): QrResult = delegate.pollQr(token)

    suspend fun getPlaybackSpec(info: EntryInfo, startMs: Long): com.insomnia.player.PlaybackSpec {
        val sources = if (!info.sources.isNullOrEmpty()) {
            info.sources
        } else {
            val delegateSources = delegate.getPlaybackSources(info.ref, startMs)
            if (delegateSources.isNotEmpty()) {
                delegateSources
            } else {
                constructStreamSources(delegate, info, activeStreamRefs)
            }
        }
        return enrichSpec(
            sources, info, startMs, endpointId,
            delegate.proxyClient.getHttpClient(),
            delegate.progressIntervalMs,
            updateEntryState = { k, v -> updateEntryStateIntercepted(k, v, info) },
        )
    }

    private suspend fun updateEntryStateIntercepted(
        key: String,
        value: String?,
        info: EntryInfo,
    ) {
        withContext(Dispatchers.IO) {
            persistLocalEntryState(info.ref, key, value)
            if (key in inheritableKeys) {
                for (ref in listOfNotNull(info.parentRef, info.seriesRef)) {
                    persistLocalEntryState(ref, key, value)
                }
            }
        }
        delegate.updateEntryState(info.ref, key, value)
    }

    private suspend fun persistLocalEntryState(itemRef: String, key: String, value: String?) {
        val store = StorageBindingsHolder.get().entryStateStore
        val appConfig = StorageBindingsHolder.get().appConfigStore
        val entryKey = EntryStateKey(endpointId, itemRef)
        when (key) {
            EntryStateKeys.POSITION_MS -> {
                val positionMs = value?.toLongOrNull() ?: return
                store.upsertPosition(entryKey, positionMs)
                EndpointCache.patchEntryUserData(endpointId, itemRef, positionMs)
                FileRelay.touch(endpointId, itemRef)
            }
            EntryStateKeys.SPEED -> {
                val speed = value?.toFloatOrNull() ?: return
                store.upsertSpeed(entryKey, speed)
            }
            EntryStateKeys.SUBTITLE_TRACK_ID -> {
                store.upsertSubtitleTrack(entryKey, value)
            }
            EntryStateKeys.AUDIO_TRACK_ID -> {
                store.upsertAudioTrack(entryKey, value)
            }
            EntryStateKeys.SUBTITLE_OFFSET_FRACTION,
            EntryStateKeys.SUBTITLE_SIZE_SCALE,
            -> {
                val prefs = appConfig.loadSubtitlePrefs()
                val updated = when (key) {
                    EntryStateKeys.SUBTITLE_OFFSET_FRACTION ->
                        prefs.copy(offsetFraction = value?.toFloatOrNull() ?: prefs.offsetFraction)
                    else ->
                        prefs.copy(sizeScale = value?.toFloatOrNull() ?: prefs.sizeScale)
                }
                appConfig.saveSubtitlePrefs(updated)
            }
            EntryStateKeys.FAVORITE -> {
                val isFavorite = value?.toBooleanStrictOrNull() ?: return
                store.upsertFavorite(entryKey, isFavorite)
                EndpointCache.patchEntryFavorite(endpointId, itemRef, isFavorite)
            }
            EntryStateKeys.PLAYING_STATE -> {
                if (value == PlayingState.STOPPED.name) {
                    evictStreamRefs(itemRef)
                }
            }
        }
    }

    // --- Flow-based entry queries ---

    fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        options: QueryOptions = QueryOptions(),
    ): Flow<EntryEmission> = progressiveFlow(
        methodName = "listEntry",
        location, startIndex, limit, options,
        fetch = { delegate.listEntry(location, startIndex, limit, options) },
    )

    fun getEntries(itemRefs: List<String>): Flow<EntryEmission> = progressiveFlow(
        methodName = "getEntries",
        itemRefs,
        fetch = { delegate.getEntries(itemRefs) },
    )

    fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String? = null,
        startIndex: Int = 0,
        limit: Int = 20,
        sortBy: SortField? = null,
        sortOrder: SortOrder = SortOrder.Descending,
    ): Flow<EntryEmission> = progressiveFlow(
        methodName = "getTaggedEntries",
        tag, scopeLocation, startIndex, limit, sortBy, sortOrder,
        fetch = { delegate.getTaggedEntries(tag, scopeLocation, startIndex, limit, sortBy, sortOrder) },
    )

    private fun progressiveFlow(
        methodName: String,
        vararg params: Any?,
        fetch: suspend () -> EntryList,
    ): Flow<EntryEmission> = kotlinx.coroutines.flow.channelFlow {
        val key = EndpointCache.buildCacheKey(methodName, endpointId, *params)

        // Cache hit: single immediate emission
        val cached = EndpointCache.get(key)
        if (cached != null) {
            val merged = mergeEntryList(cached)
            Log.d("progressive-trace", "progressiveFlow cache hit items=${merged.items.size}")
            send(EntryEmission(merged.items, merged.totalCount, isComplete = true))
            return@channelFlow
        }

        Log.d("progressive-trace", "progressiveFlow cache miss, attaching emitter")
        // Cache miss: fetch via provider, collecting emissions concurrently.
        // channelFlow lets the launched collector safely forward emissions
        // (plain `flow { }` + `launch { collect { emit } }` trips the
        // "emission from another coroutine" invariant).
        kotlinx.coroutines.coroutineScope {
            val emitter = ChannelEntryEmitter()
            delegate.entryEmitter = emitter

            val deferredResult = async(Dispatchers.IO) { fetch() }

            try {
                var emittedComplete = false
                var emissionCount = 0
                val collectorJob = launch {
                    emitter.asFlow().collect { emission ->
                        emissionCount++
                        Log.d("progressive-trace", "progressiveFlow forwarding emission #$emissionCount items=${emission.items.size} isComplete=${emission.isComplete}")
                        send(mergeEmission(emission))
                        if (emission.isComplete) emittedComplete = true
                    }
                }

                val result = deferredResult.await()
                emitter.close()
                collectorJob.join()

                EndpointCache.put(key, endpointId, result)

                Log.d("progressive-trace", "progressiveFlow fetch done providerEmissions=$emissionCount emittedComplete=$emittedComplete resultItems=${result.items.size}")
                if (!emittedComplete) {
                    val merged = mergeEntryList(result)
                    send(EntryEmission(merged.items, merged.totalCount, isComplete = true))
                }
            } finally {
                emitter.close()
                delegate.entryEmitter = null
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun mergeEmission(emission: EntryEmission): EntryEmission {
        val merged = mergeEntryList(EntryList(emission.items, emission.totalCount ?: 0))
        return emission.copy(items = merged.items, totalCount = merged.totalCount)
    }

    private suspend fun mergeEntryList(list: EntryList): EntryList {
        val store = StorageBindingsHolder.get().entryStateStore
        val merged = list.items.map { mergeEntry(it, store) }
        return EntryList(merged, list.totalCount)
    }

    private suspend fun mergeEntry(info: EntryInfo, store: EntryStateStore): EntryInfo {
        val local = store.get(endpointId, info.ref)
        val userData = UserDataMerge.merge(info.userData, local)
        val withArt = if (useGenart && info.cover == null) {
            info.copy(cover = 
                // bump version with :genart
                "http://localhost:$SERVER_PORT/genart/cover/v1/" +
                Uri.encode(endpointId) + "/" + Uri.encode(info.ref)
            )
        } else info
        return if (userData != withArt.userData) withArt.copy(userData = userData) else withArt
    }

    private fun evictStreamRefs(itemRef: String) {
        activeStreamRefs.remove(itemRef)?.forEach { ref ->
            FileRelay.evict(endpointId, ref)
        }
    }
}

private class ChannelEntryEmitter : EntryEmitter {
    private val channel = Channel<EntryEmission>(Channel.BUFFERED)

    override suspend fun emit(items: List<EntryInfo>, totalCount: Int?, isComplete: Boolean) {
        Log.d("progressive-trace", "ChannelEntryEmitter.emit items=${items.size} total=$totalCount isComplete=$isComplete")
        channel.send(EntryEmission(items, totalCount, isComplete))
    }

    fun asFlow(): Flow<EntryEmission> = channel.consumeAsFlow()
    fun close() = channel.close()
}
