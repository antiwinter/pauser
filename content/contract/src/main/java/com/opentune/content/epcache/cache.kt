package com.opentune.content.epcache

import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.EntryUserData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-level in-memory cache for list responses.
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

    private const val ITEM_TTL_MS = 5L * 60 * 60 * 1000
    private const val QUERY_TTL_MS = 5L * 60 * 60 * 1000

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

    suspend fun put(queryKey: String, endpointId: String, data: EntryList) = mutex.withLock {
        val now = System.currentTimeMillis()
        val keys = data.items.map { itemKey(endpointId, it.ref) }
        stampLocked(queryKey, keys, data.totalCount, now, entries = data.items)
    }

    suspend fun evict(queryKey: String) = mutex.withLock {
        evictQueryLocked(queryKey)
    }

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
                isFavorite = isFavorite,
                played = false,
            )
            item.copy(userData = base.copy(isFavorite = isFavorite))
        }
    }

    suspend fun getCachedItem(endpointId: String, itemRef: String): EntryInfo? = mutex.withLock {
        val entry = items[itemKey(endpointId, itemRef)] ?: return@withLock null
        if (isItemStale(entry.timestamp)) return@withLock null
        entry.info
    }

    private fun patchItem(endpointId: String, itemRef: String, transform: (EntryInfo) -> EntryInfo) {
        val entry = items[itemKey(endpointId, itemRef)] ?: return
        entry.info = transform(entry.info)
    }

    private fun isQueryStale(timestamp: Long): Boolean =
        System.currentTimeMillis() - timestamp > QUERY_TTL_MS

    private fun isItemStale(timestamp: Long): Boolean =
        System.currentTimeMillis() - timestamp > ITEM_TTL_MS

    private fun evictQueryLocked(queryKey: String) {
        val query = queries.remove(queryKey) ?: return
        for (key in query.itemKeys) {
            val entry = items[key] ?: continue
            if (isItemStale(entry.timestamp)) {
                items.remove(key)
            }
        }
    }

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
