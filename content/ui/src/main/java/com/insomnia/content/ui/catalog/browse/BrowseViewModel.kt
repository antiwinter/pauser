package com.insomnia.content.ui.catalog.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.epcache.CachingEndpointClient
import com.insomnia.content.ui.catalog.ArtUrlInjector
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class QuerySpec(
    val endpointId: String,
    val location: String?,
    val options: QueryOptions,
)

data class QueryState(
    val spec: QuerySpec,
    val items: List<EntryInfo>,
    val totalCount: Int,
    val client: CachingEndpointClient,
    val currentPageIndex: Int,
)


/**
 * Per-back-stack-entry ViewModel for BrowseRoute.
 * Data lives here until the route is popped from the back stack.
 * Navigate back → ViewModel is still alive → no re-fetch needed.
 *
 * Browse and search are the same operation: a listEntry against one or more (endpoint, location,
 * options) tuples. The ViewModel collects from a set of queries in parallel and always merges
 * results by itemRef, so duplicate emissions replace existing entries rather than appending.
 */
class BrowseViewModel : ViewModel() {
    private val _queries = MutableStateFlow<List<QueryState>>(emptyList())
    val queries: StateFlow<List<QueryState>> = _queries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _focusedQueryIndex = MutableStateFlow(0)
    private val _focusedEntryIndex = MutableStateFlow(0)

    private val _lastFocusedItemRef = MutableStateFlow<String?>(null)
    val lastFocusedItemRef: StateFlow<String?> = _lastFocusedItemRef.asStateFlow()


    fun setLastFocusedItem(queryIndex: Int, itemIndex: Int) {
        _focusedQueryIndex.value = queryIndex
        _focusedEntryIndex.value = itemIndex
    }

    fun initialize(specs: List<QuerySpec>) {
        if (_queries.value.isNotEmpty()) return
        if (specs.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val registry = EndpointClientRegistryHolder.get()
                val states = withContext(Dispatchers.IO) {
                    specs.mapNotNull { spec ->
                        val client = registry.getOrCreate(spec.endpointId) ?: return@mapNotNull null
                        QueryState(
                            spec = spec,
                            items = emptyList(),
                            totalCount = 0,
                            client = client,
                            currentPageIndex = 0,
                        )
                    }
                }
                _queries.value = states
                
                // Fetch first page for all queries
                states.forEachIndexed { index, _ ->
                    fetch(index, offset = 0)
                }
            } catch (e: Exception) {
                Timber.e(e, "initialize failed for specs=${specs.size}")
                _error.value = e.message ?: "Unknown error"
            }
        }
    }
    suspend fun buildSearchQuerySpec(term: String, scope: SearchScope): List<QuerySpec> {
        return when (scope) {
            SearchScope.Global -> {
                val registry = EndpointClientRegistryHolder.get()
                val allIds = withContext(Dispatchers.IO) { registry.allEndpointIds() }
                allIds.map { QuerySpec(it, null, QueryOptions(searchTerm = term, recursive = true)) }
            }
            SearchScope.Current -> {
                val firstQuery = _queries.value.firstOrNull()
                if (firstQuery != null) {
                    listOf(QuerySpec(firstQuery.spec.endpointId, null, QueryOptions(searchTerm = term, recursive = true)))
                } else {
                    emptyList()
                }
            }
        }
    }
    fun refresh() {
        val queries = _queries.value
        if (queries.isEmpty()) return

        val queryIndex = _focusedQueryIndex.value.coerceIn(0, queries.size - 1)
        val entryIndex = _focusedEntryIndex.value

        // Re-fetch single focused entry
        fetch(queryIndex, offset = entryIndex, limit = 1)
    }
    fun loadMore() {
        if (_loading.value) return

        val queries = _queries.value
        if (queries.isEmpty()) return

        // Multi-query mode: results are single-page only, no pagination
        if (queries.size > 1) return

        val query = queries.first()
        // Search results are loaded in one page
        if (query.spec.options.searchTerm != null) return

        // Auto next page
        fetch(0)
    }

    private fun fetch(
        queryIndex: Int,
        offset: Int? = null,
        limit: Int = PAGE_SIZE,
    ) {
        val query = _queries.value[queryIndex]
        val startIndex = offset ?: query.items.size
        
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            runCatching {
                query.client.listEntry(query.spec.location, startIndex, limit, query.spec.options)
                    .collect { emission ->
                        val injected = ArtUrlInjector.apply(emission.items, query.client.protocol, query.spec.endpointId)

                        // Merge: replace matching items, keep others, append new
                        val current = _queries.value[queryIndex]
                        val existingMap = current.items.associateBy { it.ref }
                        val updatedMap = existingMap + injected.associateBy { it.ref }
                        val newItems = updatedMap.values.toList()

                        // Replace in list
                        val queries = _queries.value.toMutableList()
                        queries[queryIndex] = current.copy(
                            items = newItems,
                            totalCount = emission.totalCount ?: newItems.size,
                            currentPageIndex = startIndex / PAGE_SIZE,
                        )
                        _queries.value = queries

                        if (emission.isComplete) {
                            Timber.d("listEntry complete endpoint=${query.spec.endpointId} location=${query.spec.location} offset=$startIndex limit=$limit: ${emission.items.size}/${emission.totalCount}")
                        }
                    }
            }.onSuccess {
                _loading.value = false
            }.onFailure { e ->
                Timber.e(e, "fetch failed for endpoint=${query.spec.endpointId} offset=$startIndex limit=$limit")
                _loading.value = false
            }
        }
    }


    companion object {
        private const val PAGE_SIZE = 30

        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrowseViewModel() as T
        }
    }
}
