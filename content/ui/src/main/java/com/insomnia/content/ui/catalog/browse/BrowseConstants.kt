package com.insomnia.content.ui.catalog.browse

import com.insomnia.content.contract.QueryOptions
import com.insomnia.storage.StorageBindingsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sentinel `location` value that means "show recent items for this endpoint" instead of a
 * normal folder listing. Each QuerySpec carries a real endpointId; the sentinel is in
 * `location`. The aggregator (epcache/recent.kt) is responsible for resolving these.
 */
const val RECENT_ROOT_LOCATION: String = "__insomnia_recent_root__"

/**
 * Multi-endpoint recent view: one spec per configured endpoint, all sentinel location.
 * Each section is capped at PAGE_SIZE in the view-model layer. Reads endpoint IDs from
 * the endpoint DAO directly (the in-memory client registry may be empty — clients are
 * built lazily on first reference).
 */
suspend fun recentMultiSpec(): List<QuerySpec> = withContext(Dispatchers.IO) {
    StorageBindingsHolder.get().endpointDao.getAll().map { endpoint ->
        QuerySpec(endpoint.endpointId, RECENT_ROOT_LOCATION, QueryOptions())
    }
}

/**
 * Single-endpoint recent view: one spec, sentinel location, no PAGE_SIZE cap.
 */
fun recentSingleSpec(endpointId: String): List<QuerySpec> =
    listOf(QuerySpec(endpointId, RECENT_ROOT_LOCATION, QueryOptions()))
