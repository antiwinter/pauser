package com.opentune.proxy.contract

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
}

interface ProxyProvider {
    val proxyType: String
    val hasCtrlUI: Boolean get() = false
    fun getFieldsSpec(): List<FormFieldSpec>
    fun createClient(values: Map<String, String>): ProxyClient
}
