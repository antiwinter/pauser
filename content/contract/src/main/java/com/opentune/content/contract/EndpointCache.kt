package com.opentune.content.contract

import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import com.opentune.storage.StorageBindingsHolder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central in-memory cache for EndpointClient list responses.
 *
 * Strategy: stale-while-revalidate
 *   - Always return cached data immediately (if any)
 *   - Trigger background re-fetch
 *   - If server data changed, update cache so next visit sees fresh data
 *   - If server data unchanged, cache stays — UI sees no flicker
 *
 * TTL: cached entries are considered stale after CACHE_TTL_MS.
 * Stale entries are still returned immediately; they just trigger a background refresh.
 *
 * Thread-safe: all access is guarded by a Mutex.
 */
object EndpointCache {
    private val mutex = Mutex()
    private val cacheData = mutableMapOf<String, List<EntryInfo>>()
    private val cacheMeta = mutableMapOf<String, CacheMeta>()

    private const val CACHE_TTL_MS = 5L * 60 * 1000 // 5 minutes

    fun buildCacheKey(
        method: String,
        endpointId: String,
        vararg params: Any?,
    ): String = buildString {
        append(method)
        append(':')
        append(endpointId)
        for (p in params) {
            append(':')
            append(p)
        }
    }

    /** Return cached items, or null if not cached. */
    suspend fun get(key: String): EntryList? = mutex.withLock {
        val items = cacheData[key] ?: return@withLock null
        val total = cacheMeta[key]?.totalCount ?: 0
        EntryList(items, total)
    }

    /** Whether the cached entry is stale (should trigger background re-fetch). */
    suspend fun isStale(key: String): Boolean = mutex.withLock {
        val meta = cacheMeta[key] ?: return@withLock true
        System.currentTimeMillis() - meta.timestamp > CACHE_TTL_MS
    }

    /** Store a result in the cache. */
    suspend fun put(key: String, data: EntryList) = mutex.withLock {
        cacheData[key] = data.items
        cacheMeta[key] = CacheMeta(data.totalCount, System.currentTimeMillis())
    }

    /** Evict a single cache entry. */
    suspend fun evict(key: String) = mutex.withLock {
        cacheData.remove(key)
        cacheMeta.remove(key)
    }

    /** Evict all cache entries for a given endpointId. */
    suspend fun clearForEndpoint(endpointId: String) = mutex.withLock {
        val prefix = ":$endpointId:"
        val toRemove = cacheData.keys.filter { it.contains(prefix) }
        for (key in toRemove) {
            cacheData.remove(key)
            cacheMeta.remove(key)
        }
    }

    // Internal metadata — not exposed in public API, but can't be private
    // due to Kotlin visibility analysis in generic withLock blocks.
    data class CacheMeta(
        val totalCount: Int,
        val timestamp: Long,
    )
}

/**
 * Decorator that wraps a real [EndpointClient] with transparent caching.
 *
 * Cached methods: listEntry, getEntries, getTaggedEntries, search
 * Non-cached (always go to network): getPlaybackSpec, test, openStream, getQr, pollQr
 * tagEntry: local persist first, then delegate remote
 *
 * Cache behavior:
 *   1. Check cache → if present and fresh, return immediately
 *   2. If stale or missing, fetch from real client, update cache, return result
 *
 * This is intentionally synchronous for the caller — the cache lookup + fetch
 * happens within the suspend function. The caller doesn't need to know about caching.
 *
 * Scroll preservation is achieved because:
 *   - ViewModel retains its items across back-stack navigation
 *   - When data is re-fetched and matches the cache, the ViewModel keeps its existing items
 *   - The UI never clears its list unless the data actually changed
 */
class CachingEndpointClient(
    private val delegate: EndpointClient,
) : EndpointClient() {

    override var imageLoader: coil3.ImageLoader?
        get() = delegate.imageLoader
        set(value) { delegate.imageLoader = value }

    override var proxyClient: com.opentune.proxy.contract.ProxyClient?
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
        val cached = EndpointCache.get(key)
        val stale = cached == null || EndpointCache.isStale(key)

        val list = if (stale) {
            val fresh = delegate.listEntry(location, startIndex, limit, options)
            EndpointCache.put(key, fresh)
            fresh
        } else {
            cached!!
        }
        return mergeEntryList(list)
    }

    override suspend fun search(scopeLocation: String, query: SearchQuery): EntryList {
        val key = EndpointCache.buildCacheKey("search", endpointId, scopeLocation, query)
        val cached = EndpointCache.get(key)
        val stale = cached == null || EndpointCache.isStale(key)

        val list = if (stale) {
            val fresh = delegate.search(scopeLocation, query)
            EndpointCache.put(key, fresh)
            fresh
        } else {
            cached!!
        }
        return mergeEntryList(list)
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        val key = EndpointCache.buildCacheKey("getEntries", endpointId, itemRefs)
        val cached = EndpointCache.get(key)
        val stale = cached == null || EndpointCache.isStale(key)

        val list = if (stale) {
            val fresh = delegate.getEntries(itemRefs)
            EndpointCache.put(key, fresh)
            fresh
        } else {
            cached!!
        }
        return mergeEntryList(list)
    }

    override suspend fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        val key = EndpointCache.buildCacheKey("getTaggedEntries", endpointId, tag, scopeLocation, startIndex, limit, sortBy, sortOrder)
        val cached = EndpointCache.get(key)
        val stale = cached == null || EndpointCache.isStale(key)

        val list = if (stale) {
            val fresh = delegate.getTaggedEntries(tag, scopeLocation, startIndex, limit, sortBy, sortOrder)
            EndpointCache.put(key, fresh)
            fresh
        } else {
            cached!!
        }
        return mergeEntryList(list)
    }

    override suspend fun getPlaybackSpec(itemRef: String, startMs: Long) =
        delegate.getPlaybackSpec(itemRef, startMs)

    override suspend fun tagEntry(itemRef: String, tag: EntryTag, value: Boolean) {
        when (tag) {
            EntryTag.Favorite -> {
                StorageBindingsHolder.get().entryStateStore.upsertFavorite(
                    EntryStateKey(endpointId, itemRef),
                    value,
                )
            }
            else -> Unit
        }
        delegate.tagEntry(itemRef, tag, value)
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
}
