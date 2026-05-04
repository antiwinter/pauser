package com.opentune.app.ui.catalog

import com.opentune.app.OpenTuneApplication
import com.opentune.storage.OpenTuneDatabase

/**
 * Active [MediaCatalogSource] plus per-screen metadata and cleanup (e.g. file-share session).
 * Routes resolve this via [resolveBrowseBinding] / [resolveSearchBinding] / [resolveDetailBinding] only.
 */
data class MediaCatalogScreenBinding(
    val catalog: MediaCatalogSource,
    val logTag: String,
    val onDispose: () -> Unit,
    /** Used by [BrowseScreen]; empty for search/detail. */
    val subtitle: String = "",
)

sealed interface CatalogResolveResult {
    data class Ok(val binding: MediaCatalogScreenBinding) : CatalogResolveResult
    data class Err(val message: String) : CatalogResolveResult
}

/** UI state after [resolveBrowseBinding] / [resolveSearchBinding] / [resolveDetailBinding]. */
sealed interface CatalogScreenBindingState {
    data object Loading : CatalogScreenBindingState
    data class Error(val message: String) : CatalogScreenBindingState
    data class Ready(val binding: MediaCatalogScreenBinding) : CatalogScreenBindingState
}

fun CatalogResolveResult.toScreenState(): CatalogScreenBindingState = when (this) {
    is CatalogResolveResult.Ok -> CatalogScreenBindingState.Ready(binding)
    is CatalogResolveResult.Err -> CatalogScreenBindingState.Error(message)
}

suspend fun resolveBrowseBinding(
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    locationDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.catalogPlugin(providerId).browseBinding(app, database, sourceId, locationDecoded)

suspend fun resolveSearchBinding(
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    scopeDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.catalogPlugin(providerId).searchBinding(app, database, sourceId, scopeDecoded)

suspend fun resolveDetailBinding(
    app: OpenTuneApplication,
    database: OpenTuneDatabase,
    providerId: String,
    sourceId: Long,
    itemRefDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.catalogPlugin(providerId).detailBinding(app, database, sourceId, itemRefDecoded)
