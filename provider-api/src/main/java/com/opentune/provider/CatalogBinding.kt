package com.opentune.provider

/**
 * Active [MediaCatalogSource] plus per-screen metadata and cleanup (e.g. file-share session).
 */
data class MediaCatalogScreenBinding(
    val catalog: MediaCatalogSource,
    val logTag: String,
    val onDispose: () -> Unit,
    /** Used by browse; empty for search/detail. */
    val subtitle: String = "",
)

sealed interface CatalogResolveResult {
    data class Ok(val binding: MediaCatalogScreenBinding) : CatalogResolveResult
    data class Err(val message: String) : CatalogResolveResult
}

/** UI state after resolving browse/search/detail bindings. */
sealed interface CatalogScreenBindingState {
    data object Loading : CatalogScreenBindingState
    data class Error(val message: String) : CatalogScreenBindingState
    data class Ready(val binding: MediaCatalogScreenBinding) : CatalogScreenBindingState
}

fun CatalogResolveResult.toScreenState(): CatalogScreenBindingState = when (this) {
    is CatalogResolveResult.Ok -> CatalogScreenBindingState.Ready(binding)
    is CatalogResolveResult.Err -> CatalogScreenBindingState.Error(message)
}
