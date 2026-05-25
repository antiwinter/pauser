package com.opentune.core.form

sealed class SubmitResult {
    data object Success : SubmitResult()
    data class Error(val message: String) : SubmitResult()
}
