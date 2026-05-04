package com.opentune.provider

sealed class SubmitResult {
    data object Success : SubmitResult()
    data class Error(val message: String) : SubmitResult()
}

interface ProviderConfigBackend {
    suspend fun submitAdd(values: Map<String, String>, serverStore: ServerStore): SubmitResult

    suspend fun submitEdit(sourceId: Long, values: Map<String, String>, serverStore: ServerStore): SubmitResult

    suspend fun loadEditFields(sourceId: Long, serverStore: ServerStore): Map<String, String>?
}
