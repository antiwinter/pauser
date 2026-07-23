package com.insomnia.content.epcache

import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryTag
import com.insomnia.storage.EntryStateEntity
import com.insomnia.storage.EntryStateKey
import com.insomnia.storage.EntryStateStore
import com.insomnia.storage.StorageBindingsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

data class RecentPerEndpoint(
    val endpointId: String,
    val displayName: String,
    val items: List<EntryInfo>,
)

/**
 * Progressive "recent" feed across all configured endpoints.
 *
 * Returns a [Flow] of snapshots. The first emission is built from local
 * [EntryStateEntity] rows (any media_state entry the user touched, or that a previous
 * recent-fetch upserted) — so the UI can render local recents on first frame even if the
 * remote calls are still in flight. After the local snapshot, each configured endpoint is
 * fetched in parallel; as each endpoint's remote `getTaggedEntries(EntryTag.Recent)`
 * completes, its items are upserted into local storage and a new aggregated snapshot is
 * emitted (containing that endpoint's fresh items, while still covering every configured
 * endpoint — endpoints whose remote call hasn't finished yet keep showing their local
 * items).
 *
 * Endpoint enumeration is sourced from the [com.insomnia.storage.EndpointDao] directly
 * (not the in-memory client registry), so the caller does not need to pre-populate
 * `EndpointClientRegistry`. Clients are built lazily per ID on first reference.
 */
fun recentFlow(
    perEndpointLimit: Int = 20,
): Flow<List<RecentPerEndpoint>> = channelFlow {
    val store = StorageBindingsHolder.get().entryStateStore
    val endpointDao = StorageBindingsHolder.get().endpointDao
    val registry = EndpointClientRegistryHolder.get()

    val endpoints = withContext(Dispatchers.IO) { endpointDao.getAll() }
    if (endpoints.isEmpty()) {
        send(emptyList())
        return@channelFlow
    }

    // 1. Local-first snapshot: read media_state per endpoint, in parallel, and emit.
    val localByEp = withContext(Dispatchers.IO) {
        coroutineScope {
            endpoints.map { ep ->
                async { ep.endpointId to store.queryRecentForEndpoint(ep.endpointId, perEndpointLimit) }
            }.awaitAll()
        }.toMap()
    }
    val initialSnapshot = endpoints.map { ep ->
        val rows = localByEp[ep.endpointId].orEmpty()
        RecentPerEndpoint(
            endpointId = ep.endpointId,
            displayName = ep.displayName,
            items = rows.map { it.toEntryInfo() },
        )
    }
    send(initialSnapshot)

    // 2. Per-endpoint remote fetch. As each one completes, upsert + emit a new snapshot.
    //    Each emission is a full aggregated snapshot so the consumer only has to swap state.
    //    We must awaitAll before returning so the channelFlow producer stays alive while
    //    per-endpoint sends happen.
    coroutineScope {
        endpoints.map { ep ->
            async(Dispatchers.IO) {
                val client = runCatching { registry.getOrCreate(ep.endpointId) }.getOrNull()
                    ?: return@async
                val final = runCatching {
                    client.getTaggedEntries(
                        tag = EntryTag.Recent,
                        scopeLocation = null,
                        startIndex = 0,
                        limit = perEndpointLimit,
                    ).toList().lastOrNull { it.isComplete }
                        ?: client.getTaggedEntries(
                            tag = EntryTag.Recent,
                            scopeLocation = null,
                            startIndex = 0,
                            limit = perEndpointLimit,
                        ).toList().lastOrNull()
                }.getOrNull()
                val items = final?.items.orEmpty()
                if (items.isEmpty()) {
                    return@async
                }
                persistRecents(ep.endpointId, items)
                // Build a fresh aggregated snapshot. Endpoints whose remote call hasn't
                // landed yet keep their local items; endpoints with no local items and no
                // remote items are simply absent from the list.
                val byEp = withContext(Dispatchers.IO) {
                    endpoints.associate { e ->
                        val rows = store.queryRecentForEndpoint(e.endpointId, perEndpointLimit)
                        e.endpointId to rows.map { it.toEntryInfo() }
                    }
                }
                val next = endpoints.mapNotNull { e ->
                    val list = byEp[e.endpointId].orEmpty()
                    if (list.isEmpty()) null
                    else RecentPerEndpoint(e.endpointId, e.displayName, list)
                }
                send(next)
            }
        }.awaitAll()
    }
}.flowOn(Dispatchers.IO)

private suspend fun persistRecents(endpointId: String, items: List<EntryInfo>) {
    val store = StorageBindingsHolder.get().entryStateStore
    for (item in items) {
        runCatching {
            store.upsertRecentMeta(
                key = EntryStateKey(endpointId, item.ref),
                title = item.title,
                type = item.type,
                cover = item.cover,
                etag = item.etag,
            )
        }
    }
}

private fun EntryStateEntity.toEntryInfo(): EntryInfo = EntryInfo(
    ref = itemRef,
    title = title.orEmpty(),
    type = type ?: "Unknown",
    cover = cover,
    etag = etag,
)
