package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.app.ui.catalog.CatalogResolveResult
import com.opentune.storage.OpenTuneDatabase

interface CatalogBindingPlugin {
    suspend fun browseBinding(
        app: OpenTuneApplication,
        database: OpenTuneDatabase,
        sourceId: Long,
        locationDecoded: String,
    ): CatalogResolveResult

    suspend fun searchBinding(
        app: OpenTuneApplication,
        database: OpenTuneDatabase,
        sourceId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult

    suspend fun detailBinding(
        app: OpenTuneApplication,
        database: OpenTuneDatabase,
        sourceId: Long,
        itemRefDecoded: String,
    ): CatalogResolveResult
}
