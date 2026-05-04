package com.opentune.provider

interface CatalogBindingPlugin {
    suspend fun browseBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        locationDecoded: String,
    ): CatalogResolveResult

    suspend fun searchBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        scopeDecoded: String,
    ): CatalogResolveResult

    suspend fun detailBinding(
        deps: CatalogBindingDeps,
        sourceId: Long,
        itemRefDecoded: String,
    ): CatalogResolveResult
}
