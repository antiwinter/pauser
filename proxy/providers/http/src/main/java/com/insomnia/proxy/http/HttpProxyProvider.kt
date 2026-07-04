package com.insomnia.proxy.http

import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.core.form.contract.FormFieldSpec
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.proxy.contract.ProxyProvider
import com.insomnia.proxy.contract.ProxyValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy

class HttpProxyProvider : ProxyProvider {

    override val proxyType: String = "http"

    override fun getFieldsSpec(): List<FormFieldSpec> = listOf(
        FormFieldSpec(
            id = "host",
            labelKey = "fld_proxy_host",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 0,
            placeholderKey = "ph_proxy_host",
        ),
        FormFieldSpec(
            id = "port",
            labelKey = "fld_proxy_port",
            kind = FormFieldKind.SingleLineText,
            required = true,
            order = 1,
            placeholderKey = "ph_proxy_port",
        ),
        FormFieldSpec(
            id = "username",
            labelKey = "fld_account_username",
            kind = FormFieldKind.SingleLineText,
            required = false,
            order = 2,
        ),
        FormFieldSpec(
            id = "password",
            labelKey = "fld_account_password",
            kind = FormFieldKind.Password,
            required = false,
            sensitive = true,
            order = 3,
        ),
    )

    override fun createClient(values: Map<String, String>): ProxyClient = HttpProxyClient(values)
}

class HttpProxyClient(private val values: Map<String, String>) : ProxyClient {

    private val host = values["host"]?.trim() ?: error("Missing proxy host")
    private val port = values["port"]?.trim()?.toIntOrNull() ?: error("Missing proxy port")
    private val username = values["username"]?.trim() ?: ""
    private val password = values["password"] ?: ""

    override val proxy: Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))

    override fun getHttpClient(): OkHttpClient = cached

    // One shared OkHttpClient (and ConnectionPool) per proxy config — stream and JS engine
    // both call getHttpClient(), so they must share a single pool through the proxy.
    private val cached: OkHttpClient by lazy {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        OkHttpClient.Builder()
            .proxy(proxy)
            .apply {
                if (username.isNotEmpty()) {
                    proxyAuthenticator(Authenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(username, password))
                            .build()
                    })
                }
            }
            .build()
    }

    override fun getConfig(): Map<String, String> = buildMap {
        put("type", "http")
        put("host", host)
        put("port", port.toString())
        if (username.isNotEmpty()) put("username", username)
    }

    override suspend fun test(): ProxyValidationResult = withContext(Dispatchers.IO) {
        try {
            if (host.isEmpty()) return@withContext ProxyValidationResult.Error("Host is required")
            if (port !in 1..65535) return@withContext ProxyValidationResult.Error("Port must be between 1 and 65535")

            val client = getHttpClient()
            val request = Request.Builder().url("http://$host:$port").build()
            client.newCall(request).execute().use { }

            ProxyValidationResult.Success(
                name = "$host:$port",
                fields = mapOf(
                    "host" to host,
                    "port" to port.toString(),
                    "username" to username,
                    "password" to password,
                ),
            )
        } catch (e: Exception) {
            ProxyValidationResult.Error(e.message ?: "Proxy validation failed")
        }
    }
}
