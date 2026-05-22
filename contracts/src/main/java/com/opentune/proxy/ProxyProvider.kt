package com.opentune.proxy

import com.opentune.provider.PlatformInfo
import com.opentune.provider.ProviderFieldSpec
import com.opentune.provider.ValidationResult
import okhttp3.OkHttpClient

interface ProxyProvider {
    val proxyType: String
    fun getFieldsSpec(): List<ProviderFieldSpec>
    suspend fun validateFields(values: Map<String, String>): ValidationResult
    fun createClient(values: Map<String, String>): OkHttpClient
    fun bootstrap(info: PlatformInfo) {}
}
