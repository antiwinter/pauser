package com.opentune.provider

data class CatalogBindingDeps(
    val serverStore: ServerStore,
    val favoritesStore: FavoritesStore,
    val progressStore: ProgressStore,
)
