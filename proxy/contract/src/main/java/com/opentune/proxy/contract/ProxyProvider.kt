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

    val ctrlUI: (@Composable (onNavigateToEdit: () -> Unit, onBack: () -> Unit) -> Unit)?
        get() = null
}

interface ProxyProvider {
    val proxyType: String
    fun getFieldsSpec(): List<FormFieldSpec>
    fun createClient(values: Map<String, String>): ProxyClient
}

/**
 * Per-endpoint wrapper around a [ProxyClient] delegate. Always present on an endpoint — when
 * no proxy is configured, [delegate] is null and a plain shared [OkHttpClient] is used.
 *
 * Owns a [HostRemapDns] installed via `newBuilder()` on the delegate's client, so runtime
 * `host.dns.remap` calls reach this endpoint's engine + media fetches. The wrapper is built
 * fresh per endpoint, so the Dns is per-endpoint — remaps never leak across endpoints, and no
 * `clearDns()` is needed (the Dns dies with the endpoint). `newBuilder()` shares the delegate's
 * connection pool and dispatcher, so there is no per-endpoint pool cost.
 */
class WrappedProxyClient(private val delegate: ProxyClient?) : ProxyClient {

    private val dns = HostRemapDns()

    private val client: OkHttpClient by lazy {
        (delegate?.getHttpClient() ?: PLAIN).newBuilder().dns(dns).build()
    }

    override fun getHttpClient(): OkHttpClient = client

    /** Register a DNS host remap on this endpoint's client. */
    fun remapDns(from: String, to: String) = dns.remap(from, to)

    override fun getConfig(): Map<String, String> = delegate?.getConfig() ?: emptyMap()

    override suspend fun test(): ProxyValidationResult =
        delegate?.test() ?: ProxyValidationResult.Success("none", emptyMap())

    override val ctrlUI
        get() = delegate?.ctrlUI

    private companion object {
        val PLAIN: OkHttpClient by lazy { OkHttpClient() }
    }
}
