package com.opentune.content.ui.catalog.browse

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.QueryOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.opentune.content.ui.catalog.ArtUrlInjector

private const val LOG_TAG = "BrowseViewModel"

/**
 * Per-back-stack-entry ViewModel for BrowseRoute.
 * Data lives here until the route is popped from the back stack.
 * Navigate back → ViewModel is still alive → no re-fetch needed.
 *
 * The data layer (CachingEndpointClient) handles network dedup:
 *   - First visit: fetches from network
 *   - Return visit: serves from cache (if stale, refreshes in background)
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

    private var client: EndpointClient? = null
    private var queryOptions: QueryOptions? = null
    private var protocol: String? = null
    private var endpointId: String? = null

    /**
     * Initialize with the client and query options once they are available.
     * Called from BrowseRoute after the EndpointClient is resolved.
     */
    fun initialize(
        client: EndpointClient,
        queryOptions: QueryOptions,
        protocol: String,
        endpointId: String,
    ) {
        this.client = client
        this.queryOptions = queryOptions
        this.protocol = protocol
        this.endpointId = endpointId
    }

    fun load() {
        // Only load if we don't have items yet — this is what preserves data
        // when navigating back (ViewModel survives, items are still here).
        if (_items.value.isNotEmpty()) {
            Log.d(LOG_TAG, "load() skipped — items already present for location=$location")
            return
        }
        val c = client ?: return
        val opts = queryOptions ?: return
        val p = protocol ?: return
        val eid = endpointId ?: return
        Log.d(LOG_TAG, "load() fetching for location=$location")
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                withContext(Dispatchers.IO) {
                    c.listEntry(location, 0, PAGE_SIZE, opts)
                }.let { result ->
                    result.copy(items = ArtUrlInjector.apply(result.items, p, eid))
                }
            }.fold(
                onSuccess = { result ->
                    _items.value = result.items
                    _totalCount.value = result.totalCount
                    Log.d(LOG_TAG, "load() complete: ${result.items.size}/${result.totalCount}")
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Unknown error"
                    Log.e(LOG_TAG, "load() failed", e)
                },
            )
            _loading.value = false
        }
    }

    fun loadMore() {
        val c = client ?: return
        val opts = queryOptions ?: return
        val p = protocol ?: return
        val eid = endpointId ?: return
        val currentItems = _items.value
        val total = _totalCount.value
        if (_loading.value || currentItems.size >= total) return
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    c.listEntry(location, currentItems.size, PAGE_SIZE, opts)
                }.let { result ->
                    result.copy(items = ArtUrlInjector.apply(result.items, p, eid))
                }
            }.onSuccess { result ->
                _items.value = currentItems + result.items
                _totalCount.value = result.totalCount
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
