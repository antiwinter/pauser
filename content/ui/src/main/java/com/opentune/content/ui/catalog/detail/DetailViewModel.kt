package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryTag
import com.opentune.content.contract.EntryUserData
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
    private val itemRef: String,
) : ViewModel() {

    // Entry info
    private val _entryInfo = MutableStateFlow<EntryInfo?>(null)
    val entryInfo: StateFlow<EntryInfo?> = _entryInfo.asStateFlow()

    // subEntry: digipak items or seasons
    private val _subEntries = MutableStateFlow<List<EntryInfo>>(emptyList())
    val subEntries: StateFlow<List<EntryInfo>> = _subEntries.asStateFlow()

    // indexes are position in array
    private val _subEntryRef = MutableStateFlow<String?>(null)
    val subEntryRef: StateFlow<String?> = _subEntryRef.asStateFlow()
    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()  
    private val _episodeIndex = MutableStateFlow<String?>(null)
    val episodeIndex: StateFlow<String?> = _episodeIndex.asStateFlow()

    fun setSubEntryRef(ref: String?) {
        _subEntryRef.value = ref
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
            Log.d(LOG_TAG, "setEntryInfo: type=${info.type}, ref=${info.ref}")
        }
    }

    fun tagEntry(tag: EntryTag, value: Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    c.tagEntry(itemRef, tag, value)
                }
                val info = _entryInfo.value ?: return@launch
                val base = info.userData ?: EntryUserData(positionMs = 0L, isFavorite = false, played = false)
                val userData = when (tag) {
                    EntryTag.Favorite -> base.copy(isFavorite = value)
                    else -> base
                }
                _entryInfo.value = info.copy(userData = userData)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "tagEntry failed: tag=$tag value=$value", e)
            }
        }
    }

    fun loadEntries(lvl: Number?) {
    
        val sub = when (lvl) {
            2 -> _subEntries
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
                    client?.listEntry(itemRef, 0, 500)
                }
                sub.value = result?.items ?: emptyList()
                Log.d(LOG_TAG, "loadSubEntries() complete: ${result?.items?.size ?: 0} subEntries")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadSubEntries() failed", e)
            }
        }
    }

    fun selectSeason(ref: String?) {
        _episodeIndex.value = ref
        _pageIndex.value = 0
        _subEntryRef.value = null
    }

    fun selectpageIndex(page: Int) {
        _pageIndex.value = page
        _subEntryRef.value = null
    }

    /** Select season and page derived from saved episode number, without clearing focus if already set. */
    fun selectSeasonAndPageForProgress(seasonRef: String, pageIndex: Int) {
        _episodeIndex.value = seasonRef
        _pageIndex.value = pageIndex
        // Don't clear subEntryRef — will be set once subEntries load
    }

    companion object {
        fun factory(itemRef: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(itemRef) as T
        }
    }
}
