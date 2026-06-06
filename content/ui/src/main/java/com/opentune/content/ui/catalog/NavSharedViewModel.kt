package com.opentune.content.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateMapOf
import com.opentune.content.contract.EntryInfo

/**
 * Shared cache for EntryInfo objects passed between routes.
 * Scoped to the NavHost — one instance shared across all routes.
 * No serialization: objects are stored as-is in memory.
 */
class NavSharedViewModel : ViewModel() {
    private val cache = mutableStateMapOf<String, EntryInfo>()

    fun cache(info: EntryInfo) {
        cache[info.id] = info
    }

    fun get(id: String): EntryInfo? = cache[id]

    fun remove(id: String) {
        cache.remove(id)
    }
}
