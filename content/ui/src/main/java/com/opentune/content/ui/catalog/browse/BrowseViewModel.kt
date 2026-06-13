package com.opentune.content.ui.catalog.browse

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.QueryOptions
import com.opentune.content.ui.catalog.ArtUrlInjector
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _lastFocusedItemRef = MutableStateFlow<String?>(null)
    val lastFocusedItemRef: StateFlow<String?> = _lastFocusedItemRef.asStateFlow()

    private val _client = MutableStateFlow<EndpointClient?>(null)
    val client: StateFlow<EndpointClient?> = _client.asStateFlow()

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    private val queryOptions = QueryOptions()
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
                Log.e(LOG_TAG, "initialize failed for endpointId=$endpointId", e)
                _error.value = e.message ?: "Unknown error"
            }
        }
    }

    fun load() {
        if (_items.value.isNotEmpty()) {
            Log.d(LOG_TAG, "load() skipped — items already present for location=$location")
            return
        }
        if (_client.value == null) return
        Log.d(LOG_TAG, "load() fetching for location=$location")
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                listPage(0, PAGE_SIZE)
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

    suspend fun listPage(startIndex: Int, limit: Int): EntryList {
        val c = _client.value ?: throw IllegalStateException("BrowseViewModel not initialized")
        val eid = endpointId ?: throw IllegalStateException("BrowseViewModel not initialized")
        return withContext(Dispatchers.IO) {
            c.listEntry(location, startIndex, limit, queryOptions)
        }.let { result ->
            result.copy(items = ArtUrlInjector.apply(result.items, c.protocol, eid))
        }
    }

    fun loadMore() {
        val currentItems = _items.value
        val total = _totalCount.value
        if (_loading.value || currentItems.size >= total) return
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                listPage(currentItems.size, PAGE_SIZE)
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
