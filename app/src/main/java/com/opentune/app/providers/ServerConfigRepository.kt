package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.SubmitResult

object ServerConfigRepository {
    suspend fun loadAddDraft(providerId: String, app: OpenTuneApplication): Map<String, String> =
        app.addServerDraftStore.load(providerId)

    suspend fun saveAddDraft(providerId: String, app: OpenTuneApplication, values: Map<String, String>) {
        app.addServerDraftStore.save(providerId, values)
    }

    suspend fun clearAddDraft(providerId: String, app: OpenTuneApplication) {
        app.addServerDraftStore.clear(providerId)
    }

    suspend fun submitAdd(
        providerId: String,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult {
        val provider = app.providerRegistry.provider(providerId)
        return provider.configBackend.submitAdd(values, app.storageBindings.serverStore)
    }

    suspend fun loadEditFields(
        providerId: String,
        app: OpenTuneApplication,
        sourceId: Long,
    ): Map<String, String> {
        val provider = app.providerRegistry.provider(providerId)
        return provider.configBackend.loadEditFields(sourceId, app.storageBindings.serverStore).orEmpty()
    }

    suspend fun submitEdit(
        providerId: String,
        sourceId: Long,
        values: Map<String, String>,
        app: OpenTuneApplication,
    ): SubmitResult {
        val provider = app.providerRegistry.provider(providerId)
        return provider.configBackend.submitEdit(sourceId, values, app.storageBindings.serverStore)
    }
}
