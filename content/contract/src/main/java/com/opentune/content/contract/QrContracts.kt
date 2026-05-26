package com.opentune.content.contract

enum class QrStatus { NEW, SCANNED, CONFIRMED, EXPIRED, CANCELED }

sealed class QrResult {
    data class QrReady(val token: String, val qrData: String) : QrResult()
    data object Scanning : QrResult()
    data object Scanned : QrResult()
    data class Confirmed(val fields: Map<String, String>) : QrResult()
    data object Expired : QrResult()
    data class Error(val message: String) : QrResult()
}
