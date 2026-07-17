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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class QuerySpec(
    val endpointId: String,
    val location: String?,
    val options: QueryOptions = QueryOptions(),
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

    private val _items = MutableStateFlow<List<EntryInfo>>(emptyList())
    val items: StateFlow<List<EntryInfo>> = _items.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastFocusedItemRef = MutableStateFlow<String?>(null)
    val lastFocusedItemRef: StateFlow<String?> = _lastFocusedItemRef.asStateFlow()

    private val _client = MutableStateFlow<CachingEndpointClient?>(null)
    val client: StateFlow<CachingEndpointClient?> = _client.asStateFlow()

    private val _activeQueries = MutableStateFlow<List<QuerySpec>>(emptyList())
    val activeQueries: StateFlow<List<QuerySpec>> = _activeQueries.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    fun setLastFocusedItemRef(ref: String) {
        _lastFocusedItemRef.value = ref
    }

    fun initialize(initialQuery: QuerySpec) {
        if (_activeQueries.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    EndpointClientRegistryHolder.get().getOrCreate(initialQuery.endpointId)
                } ?: throw IllegalStateException("No provider instance for ${initialQuery.endpointId}")
                _client.value = c
                _activeQueries.value = listOf(initialQuery)
                startCollection(listOf(initialQuery))
            } catch (e: Exception) {
                Timber.e(e, "initialize failed for endpointId=${initialQuery.endpointId}")
                _error.value = e.message ?: "Unknown error"
            }
        }
    }

    fun applySearch(term: String, scope: SearchScope) {
        viewModelScope.launch {
            val queries = when (scope) {
                SearchScope.Global -> {
                    val registry = EndpointClientRegistryHolder.get()
                    val allIds = registry.allEndpointIds()
                    allIds.map { QuerySpec(it, null, QueryOptions(searchTerm = term, recursive = true)) }
                }
                SearchScope.Current -> {
                    // Search from the first query's endpoint root, not the current browse location.
                    val firstQuery = _activeQueries.value.firstOrNull()
                    if (firstQuery != null) {
                        listOf(firstQuery.copy(location = null, options = QueryOptions(searchTerm = term, recursive = true)))
                    } else {
                        emptyList()
                    }
                }
            }

            _activeQueries.value = queries
            startCollection(queries)

            Timber.d("applySearch term=$term scope=$scope queries=${queries.size}")
        }
    }

    fun refresh() {
        val queries = _activeQueries.value
        if (queries.isEmpty()) return
        if (_items.value.isEmpty()) return

        val pageIndex = pageOfFocusedItem()
        fetch(queries, startIndex = pageIndex * PAGE_SIZE, limit = PAGE_SIZE, replaceItems = false)
    }

    private fun pageOfFocusedItem(): Int {
        val focusedRef = _lastFocusedItemRef.value ?: return 0
        val idx = _items.value.indexOfFirst { it.ref == focusedRef }
        return if (idx >= 0) idx / PAGE_SIZE else 0
    }

    fun loadMore() {
        if (_loading.value) return

        val queries = _activeQueries.value
        if (queries.isEmpty()) return

        // Search results are loaded in one page — don't paginate further.
        if (queries.any { it.options.searchTerm != null }) return

        val nextPage = _currentPageIndex.value + 1
        _currentPageIndex.value = nextPage
        fetch(queries, startIndex = nextPage * PAGE_SIZE, limit = PAGE_SIZE, replaceItems = false)
    }

    private fun startCollection(queries: List<QuerySpec>) {
        if (queries.isEmpty()) return
        _items.value = emptyList()
        _totalCount.value = 0
        _currentPageIndex.value = 0
        fetch(queries, startIndex = 0, limit = PAGE_SIZE, replaceItems = true)
    }

    private fun fetch(
        queries: List<QuerySpec>,
        startIndex: Int,
        limit: Int,
        replaceItems: Boolean,
    ) {
        if (queries.isEmpty()) return

        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            runCatching {
                val registry = EndpointClientRegistryHolder.get()

                coroutineScope {
                    val flows = queries.map { query ->
                        async(Dispatchers.IO) {
                            val client = registry.getOrCreate(query.endpointId) ?: return@async emptyList<EntryInfo>()
                            val items = mutableListOf<EntryInfo>()
                            client.listEntry(query.location, startIndex, limit, query.options)
                                .collect { emission ->
                                    val injected = ArtUrlInjector.apply(emission.items, client.protocol, query.endpointId)
                                    items.addAll(injected)
                                    if (emission.isComplete) {
                                        Timber.d("listEntry complete endpoint=${query.endpointId} location=${query.location} startIndex=$startIndex: ${emission.items.size}/${emission.totalCount}")
                                    }
                                }
                            items
                        }
                    }

                    val allItems = flows.awaitAll().flatten()
                    _items.value = if (replaceItems) {
                        mergeByRef(emptyList(), allItems)
                    } else {
                        mergeByRef(_items.value, allItems)
                    }
                    _totalCount.value = _items.value.size
                }
            }.onSuccess {
                _loading.value = false
            }.onFailure { e ->
                _error.value = e.message
                _loading.value = false
                Timber.e(e, "fetch failed startIndex=$startIndex limit=$limit")
            }
        }
    }

    private fun mergeByRef(existing: List<EntryInfo>, incoming: List<EntryInfo>): List<EntryInfo> {
        val merged = existing.associateBy { it.ref }.toMutableMap()
        incoming.forEach { merged[it.ref] = it }
        return merged.values.toList()
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
