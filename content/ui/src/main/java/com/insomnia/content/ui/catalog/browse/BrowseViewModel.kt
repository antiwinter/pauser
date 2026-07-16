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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Per-back-stack-entry ViewModel for BrowseRoute.
 * Data lives here until the route is popped from the back stack.
 * Navigate back → ViewModel is still alive → no re-fetch needed.
 *
 * Search is treated identically to browsing — it's just a listEntry with a searchTerm
 * in QueryOptions. The search modal constructs the options, and the same code path
 * (collectList) renders results.
 *
 * The data layer (CachingEndpointClient) handles network dedup:
 *   - First visit: fetches from network (or cache miss), emits progressively
 *   - Return visit: serves from cache (single immediate emission)
 */
class BrowseViewModel(
    private val location: String,
) : ViewModel() {

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

    private val _activeQueryOptions = MutableStateFlow(QueryOptions())
    val activeQueryOptions: StateFlow<QueryOptions> = _activeQueryOptions.asStateFlow()

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    private var endpointId: String? = null

    fun setLastFocusedItemRef(ref: String) {
        _lastFocusedItemRef.value = ref
    }

    fun initialize(endpointId: String) {
        if (this.endpointId != null) return
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                } ?: throw IllegalStateException("No provider instance for $endpointId")
                this@BrowseViewModel.endpointId = endpointId
                _client.value = c
                load()
            } catch (e: Exception) {
                Timber.e(e, "initialize failed for endpointId=$endpointId")
                _error.value = e.message ?: "Unknown error"
            }
        }
    }

    fun load() {
        if (_items.value.isNotEmpty()) {
            Timber.d("load() skipped — items already present for location=$location")
            return
        }
        val c = _client.value ?: return
        Timber.d("load() fetching for location=$location")
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val location = if (_activeQueryOptions.value.searchTerm != null) null else this@BrowseViewModel.location
                collectList(c, startIndex = 0, location = location, options = _activeQueryOptions.value)
            }.onFailure { e ->
                _error.value = e.message ?: "Unknown error"
                Timber.e(e, "load() failed")
            }
            _loading.value = false
        }
    }

    fun applySearch(term: String, scope: SearchScope) {
        val c = _client.value ?: return
        val opts = QueryOptions(searchTerm = term, recursive = true)
        _activeQueryOptions.value = opts
        Timber.d("applySearch term=$term scope=$scope")
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                if (scope == SearchScope.Global) {
                    collectGlobalSearch(c, term)
                } else {
                    // Search "current endpoint" — search from the endpoint root,
                    // not from the current browse location.
                    collectList(c, startIndex = 0, location = null, options = opts)
                }
            }.onFailure { e ->
                _error.value = e.message ?: "Unknown error"
                Timber.e(e, "applySearch() failed")
            }
            _loading.value = false
        }
    }

    private suspend fun collectList(
        c: CachingEndpointClient,
        startIndex: Int,
        options: QueryOptions,
        append: Boolean = false,
        location: String? = this.location,
    ) {
        val eid = endpointId ?: throw IllegalStateException("BrowseViewModel not initialized")
        c.listEntry(location, startIndex, PAGE_SIZE, options)
            .collect { emission ->
                val injectedItems = ArtUrlInjector.apply(emission.items, c.protocol, eid)
                _items.value = if (append) _items.value + injectedItems else injectedItems
                _totalCount.value = emission.totalCount ?: _items.value.size
                if (emission.isComplete) {
                    Timber.d("listEntry complete at startIndex=$startIndex: ${emission.items.size}/${emission.totalCount}")
                }
            }
    }

    private suspend fun collectGlobalSearch(c: CachingEndpointClient, term: String) {
        val registry = EndpointClientRegistryHolder.get()
        val eid = endpointId ?: throw IllegalStateException("BrowseViewModel not initialized")
        val allIds = registry.allEndpointIds()

        coroutineScope {
            val deferreds = allIds.map { otherEid ->
                async(Dispatchers.IO) {
                    runCatching {
                        val otherClient = registry.getOrCreate(otherEid) ?: return@runCatching null
                        otherClient.listEntry(null, 0, PAGE_SIZE, QueryOptions(searchTerm = term, recursive = true))
                            .first { it.isComplete }
                            .items
                    }.getOrNull().orEmpty()
                }
            }
            val merged = deferreds.awaitAll().flatten()
            val injected = ArtUrlInjector.apply(merged, c.protocol, eid)
            _items.value = injected
            _totalCount.value = injected.size
        }
    }

    fun refresh() {
        val c = _client.value ?: return
        if (_items.value.isEmpty() && _activeQueryOptions.value == QueryOptions()) return
        viewModelScope.launch {
            runCatching {
                val location = if (_activeQueryOptions.value.searchTerm != null) null else this@BrowseViewModel.location
                collectList(c, startIndex = 0, location = location, options = _activeQueryOptions.value)
            }.onFailure { e ->
                Timber.e(e, "refresh() failed")
            }
        }
    }

    fun loadMore() {
        val c = _client.value ?: return
        // Search results are loaded in one page — don't paginate further.
        if (_activeQueryOptions.value.searchTerm != null) return
        val currentItems = _items.value
        val total = _totalCount.value
        if (_loading.value || currentItems.size >= total) return
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                collectList(c, startIndex = currentItems.size, options = _activeQueryOptions.value, append = true)
            }.onFailure { e ->
                _error.value = e.message ?: "Unknown error"
            }
            _loading.value = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 30

        fun factory(location: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrowseViewModel(location) as T
        }
    }
}