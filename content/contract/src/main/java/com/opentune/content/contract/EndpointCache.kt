package com.opentune.content.contract

import com.opentune.player.PlaybackSpec
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import com.opentune.storage.StorageBindingsHolder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-level in-memory cache for [EndpointClient] list responses.
 *
 * **L1 (query):** `queryKey → ordered item keys + totalCount + timestamp`
 * **L2 (item):** `ItemKey(endpointId, itemRef) → EntryInfo + timestamp`
 *
 * TTL rules (L2 ≤ L1):
 * - On [put] and [get] hit: stamp all L2 entries, then L1 (same helper, joint epoch).
 * - On [get] miss (L1 dead/missing, or any L2 missing/dead): cascade-evict dead L2 keys
 *   from the old query list, drop L1, return null → caller refetches.
 * - [patchEntryUserData] / [patchEntryFavorite]: mutate L2 only; do not touch TTL.
 *
 * Thread-safe: all access is guarded by a [Mutex].
 */
object EndpointCache {
    private val mutex = Mutex()

    /** L1: API query → key list + meta. */
    private val queries = mutableMapOf<String, QueryEntry>()

    /** L2: canonical entry by (endpointId, itemRef). */
    private val items = mutableMapOf<ItemKey, ItemEntry>()

    private const val ITEM_TTL_MS = 5L * 60 * 1000
    private const val QUERY_TTL_MS = 5L * 60 * 1000

    data class ItemKey(val endpointId: String, val itemRef: String)

    private data class QueryEntry(
        val itemKeys: List<ItemKey>,
        val totalCount: Int,
        val timestamp: Long,
    )

    private data class ItemEntry(
        var info: EntryInfo,
        var timestamp: Long,
    )

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

    fun itemKey(endpointId: String, itemRef: String): ItemKey =
        ItemKey(endpointId, itemRef)

    /**
     * Resolve a cached query, or null on miss.
     *
     * Miss triggers [evictQueryLocked] (L1 dead → cascade dead L2 keys, drop L1).
     */
    suspend fun get(queryKey: String): EntryList? = mutex.withLock {
        val query = queries[queryKey] ?: return@withLock null
        if (isQueryStale(query.timestamp)) {
            evictQueryLocked(queryKey)
            return@withLock null
        }
        val resolved = mutableListOf<EntryInfo>()
        for (key in query.itemKeys) {
            val entry = items[key]
            if (entry == null || isItemStale(entry.timestamp)) {
                evictQueryLocked(queryKey)
                return@withLock null
            }
            resolved.add(entry.info)
        }
        val now = System.currentTimeMillis()
        stampLocked(queryKey, query.itemKeys, query.totalCount, now, entries = null)
        EntryList(resolved, query.totalCount)
    }

    /** Store a query result: L2 entries first, then L1. */
    suspend fun put(queryKey: String, endpointId: String, data: EntryList) = mutex.withLock {
        val now = System.currentTimeMillis()
        val keys = data.items.map { itemKey(endpointId, it.ref) }
        stampLocked(queryKey, keys, data.totalCount, now, entries = data.items)
    }

    /** Evict a query and cascade dead L2 keys from its list. */
    suspend fun evict(queryKey: String) = mutex.withLock {
        evictQueryLocked(queryKey)
    }

    /** Drop all L1/L2 state for [endpointId]. */
    suspend fun clearForEndpoint(endpointId: String) = mutex.withLock {
        val queryPrefix = ":$endpointId:"
        queries.keys.filter { it.contains(queryPrefix) }.toList().forEach { queries.remove(it) }
        items.keys.filter { it.endpointId == endpointId }.toList().forEach { items.remove(it) }
    }

    suspend fun patchEntryUserData(endpointId: String, itemRef: String, positionMs: Long) = mutex.withLock {
        patchItem(endpointId, itemRef) { item ->
            val base = item.userData ?: EntryUserData(
                positionMs = positionMs,
                isFavorite = false,
                played = positionMs > 0,
            )
            item.copy(userData = base.copy(positionMs = positionMs, played = positionMs > 0))
        }
    }

    suspend fun patchEntryFavorite(endpointId: String, itemRef: String, isFavorite: Boolean) = mutex.withLock {
        patchItem(endpointId, itemRef) { item ->
            val base = item.userData ?: EntryUserData(
                positionMs = 0L,
                isFavorite = isFavorite,
                played = false,
            )
            item.copy(userData = base.copy(isFavorite = isFavorite))
        }
    }

    private fun patchItem(endpointId: String, itemRef: String, transform: (EntryInfo) -> EntryInfo) {
        val entry = items[itemKey(endpointId, itemRef)] ?: return
        entry.info = transform(entry.info)
    }

    private fun isQueryStale(timestamp: Long): Boolean =
        System.currentTimeMillis() - timestamp > QUERY_TTL_MS

    private fun isItemStale(timestamp: Long): Boolean =
        System.currentTimeMillis() - timestamp > ITEM_TTL_MS

    /** L1 miss: drop query; remove dead L2 keys from its list (keep live ones). */
    private fun evictQueryLocked(queryKey: String) {
        val query = queries.remove(queryKey) ?: return
        for (key in query.itemKeys) {
            val entry = items[key] ?: continue
            if (isItemStale(entry.timestamp)) {
                items.remove(key)
            }
        }
    }

    /**
     * Stamp L2 then L1 with [now].
     * [entries] non-null → put (write info + timestamp); null → hit (timestamp only).
     */
    private fun stampLocked(
        queryKey: String,
        itemKeys: List<ItemKey>,
        totalCount: Int,
        now: Long,
        entries: List<EntryInfo>?,
    ) {
        if (entries != null) {
            for (i in itemKeys.indices) {
                items[itemKeys[i]] = ItemEntry(entries[i], now)
            }
        } else {
            for (key in itemKeys) {
                items[key]?.timestamp = now
            }
        }
        queries[queryKey] = QueryEntry(itemKeys, totalCount, now)
    }
}

/**
 * Decorator that wraps a real [EndpointClient] with transparent caching.
 *
 * Cached methods: listEntry, getEntries, getTaggedEntries, search
 * Non-cached (always go to network): getPlaybackSpec, test, openStream, getQr, pollQr
 * tagEntry: local persist first, then delegate remote
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

    override suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec {
        val spec = delegate.getPlaybackSpec(itemRef, startMs)
        val store = StorageBindingsHolder.get().entryStateStore
        val key = EntryStateKey(endpointId, itemRef)
        return spec.copy(
            hooks = CachedPlaybackHooks(
                inner = spec.hooks,
                entryStateKey = key,
                store = store,
                endpointId = endpointId,
                itemRef = itemRef,
            ),
        )
    }

    override suspend fun tagEntry(itemRef: String, tag: EntryTag, value: Boolean) {
        when (tag) {
            EntryTag.Favorite -> {
                StorageBindingsHolder.get().entryStateStore.upsertFavorite(
                    EntryStateKey(endpointId, itemRef),
                    value,
                )
                EndpointCache.patchEntryFavorite(endpointId, itemRef, value)
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
