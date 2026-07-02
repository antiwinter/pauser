package com.opentune.proxy.contract

import androidx.compose.runtime.Composable
import com.opentune.core.form.contract.FormFieldSpec
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

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

    /** The configured proxy, or null when this client has no proxy. Exposed so a wrapper can
     *  rebuild the client with a localhost/LAN-bypassing selector (see [BypassLocalProxySelector]). */
    val proxy: Proxy? get() = null

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
 *
 * When the delegate has a proxy, the rebuilt client replaces the delegate's fixed `proxy` with
 * a [BypassLocalProxySelector] so localhost/LAN targets (the sr relay at 127.0.0.1, LAN hosts)
 * skip the proxy while remote targets still use it. okhttp lets a non-null fixed `proxy`
 * preempt `proxySelector`, so the fixed proxy is nulled to let the selector take effect.
 */
class WrappedProxyClient(private val delegate: ProxyClient?) : ProxyClient {

    private val dns = HostRemapDns()

    private val client: OkHttpClient by lazy {
        val builder = (delegate?.getHttpClient() ?: PLAIN).newBuilder().dns(dns)
        val p = delegate?.proxy
        if (p != null) builder.proxy(null).proxySelector(BypassLocalProxySelector(p))
        builder.build()
    }

    override fun getHttpClient(): OkHttpClient = client

    override val proxy: Proxy? get() = delegate?.proxy

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

/**
 * Routes localhost/LAN targets direct ([Proxy.NO_PROXY]) and everything else through [base].
 * Keeps loopback relay traffic (sr at 127.0.0.1:7920) and LAN hosts off the remote proxy while
 * still proxying remote origins. Only literal local IPs/hostnames are bypassed — a hostname
 * that resolves to a LAN IP but isn't obviously local is proxied.
 */
class BypassLocalProxySelector(private val base: Proxy) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (isLocal(uri.host)) listOf(Proxy.NO_PROXY) else listOf(base)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}

    private fun isLocal(host: String?): Boolean {
        if (host == null) return false
        if (host in LOCAL_HOSTS) return true
        if (host.endsWith(".local")) return true
        return isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    private companion object {
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")
    }
}
