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
    private val itemRef: String,
) : ViewModel() {

    // Entry info
    private val _entryInfo = MutableStateFlow<EntryInfo?>(null)
    val entryInfo: StateFlow<EntryInfo?> = _entryInfo.asStateFlow()

    // Series state
    private val _seasons = MutableStateFlow<List<EntryInfo>>(emptyList())
    val seasons: StateFlow<List<EntryInfo>> = _seasons.asStateFlow()

    private val _selectedSeasonId = MutableStateFlow<String?>(null)
    val selectedSeasonId: StateFlow<String?> = _selectedSeasonId.asStateFlow()

    private val _episodes = MutableStateFlow<List<EntryInfo>>(emptyList())
    val episodes: StateFlow<List<EntryInfo>> = _episodes.asStateFlow()

    private val _totalEpisodes = MutableStateFlow(0)
    val totalEpisodes: StateFlow<Int> = _totalEpisodes.asStateFlow()

    private val _episodePage = MutableStateFlow(0)
    val episodePage: StateFlow<Int> = _episodePage.asStateFlow()

    // Generalized focused child entry id for detail back-stack restoration.
    private val _focusedChildEntryId = MutableStateFlow<String?>(null)
    val focusedChildEntryId: StateFlow<String?> = _focusedChildEntryId.asStateFlow()

    fun setFocusedChildEntryId(id: String?) {
        _focusedChildEntryId.value = id
    }

    // Digipak state
    private val _digipakChildren = MutableStateFlow<List<EntryInfo>>(emptyList())
    val digipakChildren: StateFlow<List<EntryInfo>> = _digipakChildren.asStateFlow()

    private val _singleChild = MutableStateFlow<EntryInfo?>(null)
    val singleChild: StateFlow<EntryInfo?> = _singleChild.asStateFlow()

    private var client: EndpointClient? = null
    private var protocol: String? = null
    private var endpointId: String? = null

    fun initialize(
        client: EndpointClient,
        protocol: String,
        endpointId: String,
    ) {
        this.client = client
        this.protocol = protocol
        this.endpointId = endpointId
    }

    /** Set entry info from the NavSharedViewModel cache. Called before type-specific loading begins. */
    fun setEntryInfo(info: EntryInfo) {
        if (_entryInfo.value == null) {
            _entryInfo.value = info
            Log.d(LOG_TAG, "setEntryInfo: type=${info.type}, id=${info.id}")
        }
    }

    fun loadSeasons() {
        if (_seasons.value.isNotEmpty()) {
            Log.d(LOG_TAG, "loadSeasons() skipped — already loaded")
            return
        }
        val c = client ?: return
        val p = protocol ?: return
        val eid = endpointId ?: return
        Log.d(LOG_TAG, "loadSeasons() fetching")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    c.listEntry(itemRef, 0, 500)
                }
                _seasons.value = ArtUrlInjector.apply(result.items, p, eid)
                Log.d(LOG_TAG, "loadSeasons() complete: ${result.items.size} seasons")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadSeasons() failed", e)
            }
        }
    }

    fun loadDigipakChildren() {
        if (_digipakChildren.value.isNotEmpty() || _singleChild.value != null) {
            Log.d(LOG_TAG, "loadDigipakChildren() skipped — already loaded")
            return
        }
        val c = client ?: return
        val p = protocol ?: return
        val eid = endpointId ?: return
        val childCount = _entryInfo.value?.childCount ?: 0
        Log.d(LOG_TAG, "loadDigipakChildren() fetching childCount=$childCount")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    c.listEntry(itemRef, 0, maxOf(childCount, 1))
                }
                val filtered = result.items
                if (childCount <= 1 && filtered.isNotEmpty()) {
                    _singleChild.value = filtered.first()
                } else {
                    _digipakChildren.value = ArtUrlInjector.apply(filtered, p, eid, ArtType.Thumb)
                }
                Log.d(LOG_TAG, "loadDigipakChildren() complete: ${filtered.size} children")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadDigipakChildren() failed", e)
            }
        }
    }

    fun loadEpisodes() {
        val seasonList = _seasons.value
        if (seasonList.isEmpty()) return
        val season = seasonList.firstOrNull { it.id == _selectedSeasonId.value }
            ?: seasonList.first()

        Log.d(LOG_TAG, "loadEpisodes() seasonId=${season.id} page=${_episodePage.value}")
        viewModelScope.launch {
            val c = client ?: return@launch
            val p = protocol ?: return@launch
            val eid = endpointId ?: return@launch
            try {
                val result = withContext(Dispatchers.IO) {
                    c.listEntry(season.id, _episodePage.value * 50, 50)
                }
                _episodes.value = ArtUrlInjector.apply(result.items, p, eid, ArtType.Thumb)
                    .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                _totalEpisodes.value = result.totalCount
                Log.d(LOG_TAG, "loadEpisodes() complete: ${result.items.size}/${result.totalCount}")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadEpisodes() failed", e)
            }
        }
    }

    fun selectSeason(id: String?) {
        _selectedSeasonId.value = id
        _episodePage.value = 0
        _focusedChildEntryId.value = null
    }

    fun selectEpisodePage(page: Int) {
        _episodePage.value = page
        _focusedChildEntryId.value = null
    }

    /** Select season and page derived from saved episode number, without clearing focus if already set. */
    fun selectSeasonAndPageForProgress(seasonId: String, episodePage: Int) {
        _selectedSeasonId.value = seasonId
        _episodePage.value = episodePage
        // Don't clear focusedChildEntryId — will be set once episodes load
    }

    companion object {
        fun factory(itemRef: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(itemRef) as T
        }
    }
}
