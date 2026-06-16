package com.opentune.content.contract

import com.opentune.player.EntryStateKeys
import com.opentune.player.PlaybackSpec
import com.opentune.player.PlaybackState
import com.opentune.player.PlayingState
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
                isFavorite = isFavorite,
                played = false,
            )
            item.copy(userData = base.copy(isFavorite = isFavorite))
        }
    }

    /** L2 item lookup for hierarchy refs. */
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
        val subtitlePrefs = StorageBindingsHolder.get().appConfigStore.loadSubtitlePrefs()
        val info = EndpointCache.getCachedItem(endpointId, itemRef)
            ?: EntryInfo(ref = itemRef, title = "", type = "Unknown")

        val state = PlaybackState(
            positionMs = startMs,
            speed = getInheritedValue(info, "playbackSpeed") as? Float ?: 1f,
            subtitleTrackId = getInheritedValue(info, "selectedSubtitleTrackId") as? String,
            audioTrackId = getInheritedValue(info, "selectedAudioTrackId") as? String,
            subtitleOffsetFraction = subtitlePrefs.offsetFraction,
            subtitleSizeScale = subtitlePrefs.sizeScale,
            playingState = PlayingState.STOPPED,
        )
        return spec.copy(
            state = state,
            updateEntryState = { k, v -> updateEntryStateIntercepted(k, v, info) },
        )
    }

    private suspend fun getInheritedValue(info: EntryInfo, attribute: String): Any? {
        val store = StorageBindingsHolder.get().entryStateStore
        for (ref in listOf(info.ref, info.parentRef, info.seriesRef)) {
            if (ref == null) continue
            val row = store.get(endpointId, ref) ?: continue
            val value = when (attribute) {
                "playbackSpeed" -> row.playbackSpeed.takeUnless { it == 1f }
                "selectedSubtitleTrackId" -> row.selectedSubtitleTrackId?.takeIf { it.isNotEmpty() }
                "selectedAudioTrackId" -> row.selectedAudioTrackId?.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (value != null) return value
        }
        return null
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
            EntryStateKeys.PLAYING_STATE -> Unit
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
}
