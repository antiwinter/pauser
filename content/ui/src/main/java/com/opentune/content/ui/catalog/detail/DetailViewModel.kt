package com.opentune.content.ui.catalog.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.QueryOptions
import com.opentune.content.contract.SortField
import com.opentune.content.contract.SortOrder
import com.opentune.content.contract.EntryInfo
import com.opentune.player.EntryStateKeys
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.StorageBindingsHolder
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

private const val LOADER_PAGE_SIZE = 100

private val episodeListOptions = QueryOptions(
    sortBy = SortField.IndexNumber,
    sortOrder = SortOrder.Ascending,
)

enum class DetailRefreshScope {
    /** Current entry via getEntries. */
    Header,
    /** Header plus sub-entry lists (episodes, digipak children). */
    Lists,
}

/**
 * Per-back-stack-entry ViewModel for DetailRoute.
 * Survives navigation back/forward — data is cached until the route is popped.
 */
class DetailViewModel(
    private val itemRef: String,
) : ViewModel() {

    private val _entryInfo = MutableStateFlow<EntryInfo?>(null)
    val entryInfo: StateFlow<EntryInfo?> = _entryInfo.asStateFlow()

    private val _subEntries = MutableStateFlow<List<EntryInfo>>(emptyList())
    val subEntries: StateFlow<List<EntryInfo>> = _subEntries.asStateFlow()

    private val _subEntryIndex = MutableStateFlow<Int?>(null)
    val subEntryIndex: StateFlow<Int?> = _subEntryIndex.asStateFlow()

    private val _episodeIndex = MutableStateFlow<Int?>(null)
    val episodeIndex: StateFlow<Int?> = _episodeIndex.asStateFlow()

    private val _episodes = MutableStateFlow<Map<Int, EntryInfo>>(emptyMap())
    val episodes: StateFlow<Map<Int, EntryInfo>> = _episodes.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _client = MutableStateFlow<EndpointClient?>(null)
    val client: StateFlow<EndpointClient?> = _client.asStateFlow()

    val imageLoader: ImageLoader?
        get() = _client.value?.imageLoader

    private var endpointId: String? = null
    private var appConfigStore: AppPrefsStore? = null
    private var episodeFetchJob: Job? = null

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
                    _entryInfo.value = initialInfo
                    Timber.d("Using cached EntryInfo: type=${initialInfo.type}")
                }
                loadSubEntries(c)
            } catch (e: Exception) {
                Timber.e(e, "initialize failed for endpointId=$endpointId")
            }
        }
    }

    fun updateEntryState(key: String, value: String) {
        val c = _client.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    c.updateEntryState(itemRef, key, value)
                }
                if (key == EntryStateKeys.FAVORITE) {
                    refresh(DetailRefreshScope.Header)
                }
            } catch (e: Exception) {
                Timber.e(e, "updateEntryState failed: key=$key")
            }
        }
    }

    /** Re-fetch from the caching client; updates flows in place without clearing lists. */
    fun refresh(scope: DetailRefreshScope) {
        viewModelScope.launch {
            val c = _client.value ?: return@launch
            if (endpointId == null) return@launch
            // Clear episode selection so the resume effect can re-trigger focus after data update.
            // Must happen *before* the IO suspension so compose processes the null state.
            if (scope == DetailRefreshScope.Lists) {
                _episodeIndex.value = null
            }
            try {
                withContext(Dispatchers.IO) {
                    refreshHeader(c)
                    if (scope == DetailRefreshScope.Lists) {
                        refreshLists(c)
                    }
                }
                Timber.d("refresh($scope) complete")
            } catch (e: Exception) {
                Timber.e(e, "refresh($scope) failed")
            }
        }
    }

    private suspend fun refreshHeader(c: EndpointClient) {
        val info = c.getEntries(listOf(itemRef)).items.firstOrNull() ?: return
        _entryInfo.value = info
    }

    private suspend fun refreshLists(c: EndpointClient) {
        loadSubEntries(c)
        when (_entryInfo.value?.type) {
            "Series" -> refreshEpisodes()
        }
    }

    private suspend fun refreshEpisodes() {
        val subIdx = _subEntryIndex.value ?: return
        if (subIdx !in _subEntries.value.indices) return
        val epIdx = _episodeIndex.value ?: 0
        fetchEpisodePage(subIdx, pageStart(epIdx))
    }

    private suspend fun loadSubEntries(c: EndpointClient) {
        if (_subEntries.value.isNotEmpty()) return
        val result = c.listEntry(itemRef, 0, 500)
        _subEntries.value = result.items
        Timber.d("loadSubEntries: ${result.items.size} items")
    }

    fun setSubEntry(index: Int) {
        val entries = _subEntries.value
        if (entries.isEmpty() || index !in entries.indices) return
        _subEntryIndex.value = index
        Timber.d("setSubEntry: index=$index ref=${entries[index].ref}")
    }

    fun setEpisode(subEntryIdx: Int, episodeIdx: Int) {
        val subEntries = _subEntries.value
        if (subEntries.isEmpty()) return

        if (subEntryIdx !in subEntries.indices) {
            setEpisode(0, 0)
            return
        }

        if (subEntryIdx == _subEntryIndex.value && _episodes.value[episodeIdx] != null) {
            _episodeIndex.value = episodeIdx
            return
        }

        val mergePages = subEntryIdx == _subEntryIndex.value
        _subEntryIndex.value = subEntryIdx
        episodeFetchJob?.cancel()
        episodeFetchJob = viewModelScope.launch {
            fetchEpisodePage(subEntryIdx, pageStart(episodeIdx), mergePages)
            if (_episodes.value[episodeIdx] != null) {
                _episodeIndex.value = episodeIdx
            } else if (episodeIdx != 0) {
                setEpisode(subEntryIdx, 0)
            }
        }
    }

    fun nextEpisode() {
        val subIdx = _subEntryIndex.value ?: return
        val episodeIdx = _episodeIndex.value ?: return
        val total = _totalCount.value
        val subEntries = _subEntries.value

        when {
            total > 0 && episodeIdx + 1 < total -> setEpisode(subIdx, episodeIdx + 1)
            subIdx + 1 < subEntries.size -> setEpisode(subIdx + 1, 0)
            else -> Timber.d("nextEpisode: end of series")
        }
    }

    private suspend fun fetchEpisodePage(seasonIdx: Int, start: Int, mergePages: Boolean = true) {
        val c = _client.value ?: return
        val subEntry = _subEntries.value.getOrNull(seasonIdx) ?: return

        val result = withContext(Dispatchers.IO) {
            c.listEntry(subEntry.ref, start, LOADER_PAGE_SIZE, episodeListOptions)
        }

        if (result.totalCount > 0) {
            _totalCount.value = result.totalCount
        }

        val items = result.items
        if (items.isEmpty()) return

        val episodeMap = if (mergePages) {
            _episodes.value.toMutableMap()
        } else {
            mutableMapOf()
        }
        items.forEachIndexed { i, item -> episodeMap[start + i] = item }
        _episodes.value = episodeMap

        Timber.d("fetchEpisodePage: season=$seasonIdx start=$start count=${items.size} total=${result.totalCount}")
    }

    private fun pageStart(episodeIdx: Int): Int =
        (episodeIdx / LOADER_PAGE_SIZE) * LOADER_PAGE_SIZE

    companion object {
        fun factory(itemRef: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(itemRef) as T
        }
    }
}
