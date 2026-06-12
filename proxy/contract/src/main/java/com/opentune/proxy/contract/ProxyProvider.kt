package com.opentune.proxy.contract

import androidx.compose.runtime.Composable
import com.opentune.core.form.contract.FormFieldSpec
import okhttp3.OkHttpClient

sealed class ProxyValidationResult {
    data class Success(
        val name: String,
        val fields: Map<String, String>,
    ) : ProxyValidationResult()

    data class Error(val message: String) : ProxyValidationResult()
}

interface ProxyClient {
    fun getHttpClient(): OkHttpClient = OkHttpClient()
    fun getConfig(): Map<String, String>
    suspend fun test(): ProxyValidationResult

    val ctrlUI: (@Composable (onNavigateToEdit: () -> Unit, onDismiss: () -> Unit) -> Unit)?
        get() = null
}

interface ProxyProvider {
    val proxyType: String
    fun getFieldsSpec(): List<FormFieldSpec>
    fun createClient(values: Map<String, String>): ProxyClient
}
