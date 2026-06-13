package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.catalog.ArtType
import com.opentune.content.ui.catalog.ArtUrlInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "DetailViewModel"

/**
 * Per-back-stack-entry ViewModel for DetailRoute.
 * Survives navigation back/forward — data is cached until the route is popped.
 */
class DetailViewModel(
    private val itemId: String,
) : ViewModel() {

    // Entry info
    private val _entryInfo = MutableStateFlow<EntryInfo?>(null)
    val entryInfo: StateFlow<EntryInfo?> = _entryInfo.asStateFlow()

    // subEntry: digipak items or seasons
    private val _subEntries = MutableStateFlow<List<EntryInfo>>(emptyList())
    val subEntries: StateFlow<List<EntryInfo>> = _subEntries.asStateFlow()

    // indexes are position in array
    private val _subEntryIndex = MutableStateFlow<Int>(0)
    val subEntryIndex: StateFlow<Int> = _subEntryIndex.asStateFlow()
    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()  
    private val _episodeIndex = MutableStateFlow<Int>(0)
    val episodeIndex: StateFlow<Int> = _episodeIndex.asStateFlow()

    fun setsubEntryIndex(id: String?) {
        _subEntryIndex.value = id
    }

    private var client: EndpointClient? = null
    private var endpointId: String? = null

    fun initialize(
        client: EndpointClient,
        endpointId: String,
    ) {
        this.client = client
        this.endpointId = endpointId
    }

    /** Set entry info from the NavSharedViewModel cache. Called before type-specific loading begins. */
    fun setEntryInfo(info: EntryInfo) {
        if (_entryInfo.value == null) {
            _entryInfo.value = info
            Log.d(LOG_TAG, "setEntryInfo: type=${info.type}, id=${info.id}")
        }
    }

    fun loadEntries(val lvl: Number?) {
    
        val sub = when (lvl) {
            2 -> _episodes
            else -> _subEntries
        }

        if (sub.value.isNotEmpty()) {
            Log.d(LOG_TAG, "loadSubEntries() skipped — already loaded")
            return
        }

        Log.d(LOG_TAG, "loadSubEntries() fetching")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client?.listEntry(itemId, 0, 500)
                }
                sub.value = result?.items ?: emptyList()
                Log.d(LOG_TAG, "loadSubEntries() complete: ${result.items.size} subEntries")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadSubEntries() failed", e)
            }
        }
    }

    fun selectSeason(id: String?) {
        _episodeIndex.value = id
        _pageIndex.value = 0
        _subEntryIndex.value = null
    }

    fun selectpageIndex(page: Int) {
        _pageIndex.value = page
        _subEntryIndex.value = null
    }

    /** Select season and page derived from saved episode number, without clearing focus if already set. */
    fun selectSeasonAndPageForProgress(seasonId: String, pageIndex: Int) {
        _episodeIndex.value = seasonId
        _pageIndex.value = pageIndex
        // Don't clear subEntryIndex — will be set once subEntries load
    }

    companion object {
        fun factory(itemId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(itemId) as T
        }
    }
}
