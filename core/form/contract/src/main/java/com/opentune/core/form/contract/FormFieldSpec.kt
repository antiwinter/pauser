package com.opentune.core.form.contract

enum class QrStatus { NEW, SCANNED, CONFIRMED, EXPIRED, CANCELED }

sealed class QrResult {
    data class QrReady(val token: String, val qrData: String) : QrResult()
    data object Scanning : QrResult()
    data object Scanned : QrResult()
    data class Confirmed(val fields: Map<String, String>) : QrResult()
    data object Expired : QrResult()
    data class Error(val message: String) : QrResult()
}

enum class FormFieldKind {
    Text,
    SingleLineText,
    Password,
    ProxySelector,
    QrCode,
}

data class FormFieldSpec(
    val id: String,
    val labelKey: String,
    val kind: FormFieldKind,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val order: Int = 0,
    val placeholderKey: String? = null,
    val defaultValue: String? = null,
    val identity: Boolean = false,
)
