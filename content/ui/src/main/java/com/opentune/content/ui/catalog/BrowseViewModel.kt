package com.opentune.content.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.QueryOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-back-stack-entry ViewModel for BrowseRoute.
 * Survives navigation back/forward — data is cached until the route is popped.
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

    private var loaded = false
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
        if (loaded) return
        val c = client ?: return
        val opts = queryOptions ?: return
        val p = protocol ?: return
        val eid = endpointId ?: return
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
                    loaded = true
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Unknown error"
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

        fun factory(
            location: String,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrowseViewModel(location) as T
        }
    }
}
