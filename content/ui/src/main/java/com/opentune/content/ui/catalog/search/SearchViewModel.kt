package com.opentune.content.ui.catalog.search

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.SearchQuery
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SearchViewModel"

class SearchViewModel : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val results = mutableStateListOf<EntryInfo>()

    private val _lastFocusedItemRef = MutableStateFlow<String?>(null)
    val lastFocusedItemRef: StateFlow<String?> = _lastFocusedItemRef.asStateFlow()

    private val _client = MutableStateFlow<EndpointClient?>(null)
    val client: StateFlow<EndpointClient?> = _client.asStateFlow()

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    private var searchFn: (suspend (String) -> List<EntryInfo>)? = null
    private var initializedKey: String? = null

    init {
        viewModelScope.launch {
            _query.collectLatest { q ->
                delay(280)
                val trimmed = q.trim()
                if (trimmed.isEmpty()) {
                    results.clear()
                    _searching.value = false
                    return@collectLatest
                }
                _searching.value = true
                try {
                    val fetched = withContext(Dispatchers.IO) {
                        searchFn?.invoke(trimmed) ?: emptyList()
                    }
                    results.clear()
                    results.addAll(fetched)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "search", e)
                    results.clear()
                } finally {
                    _searching.value = false
                }
            }
        }
    }

    fun initialize(endpointId: String, scopeLocation: String) {
        val key = "$endpointId:$scopeLocation"
        if (initializedKey == key) return
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                } ?: throw IllegalStateException("No instance for $endpointId")
                _client.value = c
                searchFn = { q ->
                    c.search(scopeLocation, SearchQuery(term = q)).items
                }
                initializedKey = key
            } catch (e: Exception) {
                Log.e(LOG_TAG, "initialize failed for endpointId=$endpointId", e)
                _initError.value = e.message ?: "Unknown error"
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setLastFocusedItemRef(ref: String?) {
        _lastFocusedItemRef.value = ref
    }

    fun refresh() {
        val q = _query.value.trim()
        val fn = searchFn ?: return
        if (q.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { fn(q) }
            }.onSuccess { fetched ->
                results.clear()
                results.addAll(fetched)
            }.onFailure { e ->
                Log.e(LOG_TAG, "refresh() failed", e)
            }
        }
    }
}
