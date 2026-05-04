package com.opentune.provider

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
