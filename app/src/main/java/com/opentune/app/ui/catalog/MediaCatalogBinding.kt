package com.opentune.app.ui.catalog

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.CatalogBindingDeps
import com.opentune.provider.CatalogResolveResult

fun OpenTuneApplication.catalogBindingDeps(): CatalogBindingDeps =
    CatalogBindingDeps(
        serverStore = storageBindings.serverStore,
        favoritesStore = storageBindings.favoritesStore,
        progressStore = storageBindings.progressStore,
    )

suspend fun resolveBrowseBinding(
    app: OpenTuneApplication,
    providerId: String,
    sourceId: Long,
    locationDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.provider(providerId).catalogPlugin.browseBinding(
        app.catalogBindingDeps(),
        sourceId,
        locationDecoded,
    )

suspend fun resolveSearchBinding(
    app: OpenTuneApplication,
    providerId: String,
    sourceId: Long,
    scopeDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.provider(providerId).catalogPlugin.searchBinding(
        app.catalogBindingDeps(),
        sourceId,
        scopeDecoded,
    )

suspend fun resolveDetailBinding(
    app: OpenTuneApplication,
    providerId: String,
    sourceId: Long,
    itemRefDecoded: String,
): CatalogResolveResult =
    app.providerRegistry.provider(providerId).catalogPlugin.detailBinding(
        app.catalogBindingDeps(),
        sourceId,
        itemRefDecoded,
    )
