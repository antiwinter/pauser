package com.insomnia.content.epcache

import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryList
import com.insomnia.content.contract.EntryTag
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointValidationResult
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SearchQuery
import com.insomnia.content.contract.SortField
import com.insomnia.content.contract.SortOrder
import com.insomnia.content.contract.FileRelay
import com.insomnia.content.contract.UserDataMerge
import com.insomnia.player.EntryStateKeys
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlayingState
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.storage.EntryStateKey
import com.insomnia.storage.EntryStateStore
import com.insomnia.storage.StorageBindingsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decorator that wraps a real [EndpointClient] with transparent caching.
 *
 * Cached methods: listEntry, getEntries, getTaggedEntries, search
 * Non-cached (always go to network): getPlaybackSources, test, openStream, getQr, pollQr
 * [updateEntryState]: local persist + cache patch, then delegate remote
 */
class CachingEndpointClient(
    private val delegate: EndpointClient,
) : EndpointClient() {

    private val inheritableKeys = setOf(
        EntryStateKeys.SPEED,
        EntryStateKeys.SUBTITLE_TRACK_ID,
        EntryStateKeys.AUDIO_TRACK_ID,
    )

    private val activeStreamRefs = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    override var imageLoader: coil3.ImageLoader?
        get() = delegate.imageLoader
        set(value) { delegate.imageLoader = value }

    override var proxyClient: ProxyClient
        get() = delegate.proxyClient
        set(value) { delegate.proxyClient = value }

    override var endpointId: String
        get() = delegate.endpointId
        set(value) { delegate.endpointId = value }

    override var protocol: String
        get() = delegate.protocol
        set(value) { delegate.protocol = value }

    override suspend fun test(): EndpointValidationResult = delegate.test()

    override suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        options: QueryOptions,
    ): EntryList {
        val key = EndpointCache.buildCacheKey("listEntry", endpointId, location, startIndex, limit, options)
        return cachedList(key) { delegate.listEntry(location, startIndex, limit, options) }
    }

    override suspend fun search(scopeLocation: String, query: SearchQuery): EntryList {
        val key = EndpointCache.buildCacheKey("search", endpointId, scopeLocation, query)
        return cachedList(key) { delegate.search(scopeLocation, query) }
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        val key = EndpointCache.buildCacheKey("getEntries", endpointId, itemRefs)
        return cachedList(key) { delegate.getEntries(itemRefs) }
    }

    override suspend fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        val key = EndpointCache.buildCacheKey(
            "getTaggedEntries", endpointId, tag, scopeLocation, startIndex, limit, sortBy, sortOrder,
        )
        return cachedList(key) {
            delegate.getTaggedEntries(tag, scopeLocation, startIndex, limit, sortBy, sortOrder)
        }
    }

    private suspend fun cachedList(key: String, fetch: suspend () -> EntryList): EntryList {
        val list = EndpointCache.get(key)
            ?: fetch().also { EndpointCache.put(key, endpointId, it) }
        return mergeEntryList(list)
    }

    override suspend fun getPlaybackSources(itemRef: String): List<PlaybackSource> {
        return delegate.getPlaybackSources(itemRef)
    }

    suspend fun getPlaybackSpec(info: EntryInfo, startMs: Long): com.insomnia.player.PlaybackSpec {
        val sources = if (!info.sources.isNullOrEmpty()) {
            info.sources
        } else {
            val delegateSources = delegate.getPlaybackSources(info.ref)
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

    override suspend fun updateEntryState(itemRef: String, key: String, value: String?) {
        withContext(Dispatchers.IO) {
            persistLocalEntryState(itemRef, key, value)
        }
        delegate.updateEntryState(itemRef, key, value)
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

    private suspend fun mergeEntryList(list: EntryList): EntryList {
        val store = StorageBindingsHolder.get().entryStateStore
        val merged = list.items.map { mergeEntry(it, store) }
        return EntryList(merged, list.totalCount)
    }

    private suspend fun mergeEntry(info: EntryInfo, store: EntryStateStore): EntryInfo {
        val local = store.get(endpointId, info.ref)
        val userData = UserDataMerge.merge(info.userData, local)
        return if (userData != info.userData) info.copy(userData = userData) else info
    }

    override suspend fun openStream(itemRef: String) =
        delegate.openStream(itemRef)

    override suspend fun getQr() = delegate.getQr()

    override suspend fun pollQr(token: String) = delegate.pollQr(token)

    private fun evictStreamRefs(itemRef: String) {
        activeStreamRefs.remove(itemRef)?.forEach { ref ->
            FileRelay.evict(endpointId, ref)
        }
    }
}
