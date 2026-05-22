package com.opentune.content.contract

enum class FormFieldKind {
    Text,
    SingleLineText,
    Password,
}

data class FormFieldSpec(
    val id: String,
    val labelKey: String,
    val kind: FormFieldKind,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val order: Int = 0,
    val placeholderKey: String? = null,
)
