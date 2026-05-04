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

/**
 * Declarative add/edit form field. [labelKey] is resolved to user-visible text in the app layer.
 */
data class ServerFieldSpec(
    val id: String,
    val labelKey: String,
    val kind: ServerFieldKind,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val order: Int = 0,
    val placeholderKey: String? = null,
)

enum class ServerFieldKind {
    Text,
    SingleLineText,
    Password,
}

/** Route / registry ids for catalog and server configuration. Literal values live only in this object. */
object OpenTuneProviderIds {
    const val HTTP_LIBRARY: String = "emby"
    const val FILE_SHARE: String = "smb"

    fun isKnown(providerId: String): Boolean =
        providerId == HTTP_LIBRARY || providerId == FILE_SHARE
}
