package com.opentune.content.ui.catalog.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryTag
import com.opentune.content.contract.EntryUserData
import com.opentune.content.ui.catalog.ArtType
import com.opentune.content.ui.catalog.ArtUrlInjector
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import coil3.ImageLoader
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

    private val _entryInfo = MutableStateFlow<EntryInfo?>(null)
    val entryInfo: StateFlow<EntryInfo?> = _entryInfo.asStateFlow()

    private val _seasons = MutableStateFlow<List<EntryInfo>>(emptyList())
    val seasons: StateFlow<List<EntryInfo>> = _seasons.asStateFlow()

    private val _episodeIndex = MutableStateFlow<String?>(null)
    val seasonIndex: StateFlow<String?> = _episodeIndex.asStateFlow()

    private val _episodes = MutableStateFlow<List<EntryInfo>>(emptyList())
    val episodes: StateFlow<List<EntryInfo>> = _episodes.asStateFlow()

    private val _totalEpisodes = MutableStateFlow(0)
    val totalEpisodes: StateFlow<Int> = _totalEpisodes.asStateFlow()

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _subEntryRef = MutableStateFlow<String?>(null)
    val subEntryRef: StateFlow<String?> = _subEntryRef.asStateFlow()

    private val _digipakChildren = MutableStateFlow<List<EntryInfo>>(emptyList())
    val digipakChildren: StateFlow<List<EntryInfo>> = _digipakChildren.asStateFlow()

    private val _singleChild = MutableStateFlow<EntryInfo?>(null)
    val singleChild: StateFlow<EntryInfo?> = _singleChild.asStateFlow()

    fun setSubEntryRef(ref: String?) {
        _subEntryRef.value = ref
    }

    private val _client = MutableStateFlow<EndpointClient?>(null)
    val client: StateFlow<EndpointClient?> = _client.asStateFlow()

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    private var endpointId: String? = null
    private var appConfigStore: AppPrefsStore? = null

    val appConfig: AppPrefsStore
        get() = requireNotNull(appConfigStore) { "DetailViewModel not initialized" }

    val entryStateKey: EntryStateKey
        get() = EntryStateKey(requireNotNull(endpointId) { "DetailViewModel not initialized" }, itemRef)

    fun initialize(endpointId: String, initialInfo: EntryInfo? = null) {
        if (this.endpointId != null) return
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    EndpointClientRegistryHolder.get().getOrCreate(endpointId)
                } ?: throw IllegalStateException("No provider instance for $endpointId")
                this@DetailViewModel.endpointId = endpointId
                appConfigStore = StorageBindingsHolder.get().appConfigStore
                _client.value = c
                if (initialInfo != null && _entryInfo.value == null) {
                    setEntryInfo(ArtUrlInjector.applyInfo(initialInfo, c.protocol))
                    Log.d(LOG_TAG, "Using cached EntryInfo: type=${initialInfo.type}")
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "initialize failed for endpointId=$endpointId", e)
            }
        }
    }

    fun setEntryInfo(info: EntryInfo) {
        if (_entryInfo.value == null) {
            _entryInfo.value = info
            Log.d(LOG_TAG, "setEntryInfo: type=${info.type}, ref=${info.ref}")
        }
    }

    fun tagEntry(tag: EntryTag, value: Boolean) {
        val c = _client.value ?: return
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

    fun loadSeasons() {
        if (_seasons.value.isNotEmpty()) {
            Log.d(LOG_TAG, "loadSeasons() skipped — already loaded")
            return
        }
        val c = _client.value ?: return
        val eid = endpointId ?: return
        Log.d(LOG_TAG, "loadSeasons() fetching")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    c.listEntry(itemRef, 0, 500)
                }
                _seasons.value = ArtUrlInjector.apply(result.items, c.protocol, eid)
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
        val c = _client.value ?: return
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
                    _digipakChildren.value = ArtUrlInjector.apply(filtered, c.protocol, eid, ArtType.Thumb)
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
        val season = seasonList.firstOrNull { it.ref == _episodeIndex.value }
            ?: seasonList.first()

        Log.d(LOG_TAG, "loadEpisodes() seasonRef=${season.ref} page=${_pageIndex.value}")
        viewModelScope.launch {
            val c = _client.value ?: return@launch
            val eid = endpointId ?: return@launch
            try {
                val result = withContext(Dispatchers.IO) {
                    c.listEntry(season.ref, _pageIndex.value * 50, 50)
                }
                _episodes.value = ArtUrlInjector.apply(result.items, c.protocol, eid, ArtType.Thumb)
                    .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                _totalEpisodes.value = result.totalCount
                Log.d(LOG_TAG, "loadEpisodes() complete: ${result.items.size}/${result.totalCount}")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadEpisodes() failed", e)
            }
        }
    }

    fun setSeason(ref: String?) {
        _episodeIndex.value = ref
        _pageIndex.value = 0
        _subEntryRef.value = null
    }

    fun selectpageIndex(page: Int) {
        _pageIndex.value = page
        _subEntryRef.value = null
    }

    fun selectSeasonAndPageForProgress(seasonRef: String, pageIndex: Int) {
        _episodeIndex.value = seasonRef
        _pageIndex.value = pageIndex
    }

    companion object {
        fun factory(itemRef: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(itemRef) as T
        }
    }
}
