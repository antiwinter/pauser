package com.opentune.provider

import android.content.Context

interface OpenTuneProvider {
    val providerId: String

    fun addFields(): List<ServerFieldSpec>

    fun editFields(): List<ServerFieldSpec>

    val catalogPlugin: CatalogBindingPlugin

    val configBackend: ProviderConfigBackend

    suspend fun resolvePlayback(
        deps: PlaybackResolveDeps,
        sourceId: Long,
        itemRef: String,
        startMs: Long,
    ): PlaybackSpec

    fun bootstrap(context: Context)
}
