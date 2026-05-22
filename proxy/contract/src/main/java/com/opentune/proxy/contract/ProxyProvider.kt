package com.opentune.proxy.contract

import com.opentune.content.contract.PlatformInfo
import com.opentune.content.contract.FormFieldSpec
import okhttp3.OkHttpClient

sealed class ProxyValidationResult {
    data class Success(
        val name: String,
        val fields: Map<String, String>,
    ) : ProxyValidationResult()

    data class Error(val message: String) : ProxyValidationResult()
}

interface ProxyProvider {
    val proxyType: String
    fun getFieldsSpec(): List<FormFieldSpec>
    suspend fun validateFields(values: Map<String, String>): ProxyValidationResult
    fun createClient(values: Map<String, String>): OkHttpClient
    fun bootstrap(info: PlatformInfo) {}
}
