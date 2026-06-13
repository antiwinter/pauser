package com.opentune.content.ui.catalog.search

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EntryInfo
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

    private var searchFn: (suspend (String) -> List<EntryInfo>)? = null

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

    fun initialize(fn: suspend (String) -> List<EntryInfo>) {
        searchFn = fn
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setLastFocusedItemRef(ref: String?) {
        _lastFocusedItemRef.value = ref
    }
}
