package com.opentune.proxy.clash

import androidx.compose.runtime.Composable
import com.opentune.proxy.contract.ProxyClient
import com.opentune.proxy.contract.ProxyValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy

class ClashProxyClient(private val values: Map<String, String>) : ProxyClient {

    private val controllerUrl = values["url"]?.trimEnd('/') ?: error("Missing Clash controller url")
    private val secret = values["secret"] ?: ""
    private val selectorName = values["name"]?.ifBlank { null } ?: "GLOBAL"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val controllerClient = OkHttpClient()

    private fun baseRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$controllerUrl$path")
            .apply { if (secret.isNotBlank()) header("Authorization", "Bearer $secret") }

    override fun getHttpClient(): OkHttpClient {
        // Clash default mixed-port proxy
        val host = runCatching {
            java.net.URL(controllerUrl).host
        }.getOrDefault("127.0.0.1")
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, 7890)))
            .build()
    }

    override fun getConfig(): Map<String, String> = buildMap {
        val host = runCatching { java.net.URL(controllerUrl).host }.getOrDefault("127.0.0.1")
        put("type", "clash")
        put("host", host)
        put("port", "7890")
        put("controllerUrl", controllerUrl)
        put("selectorName", selectorName)
    }

    override suspend fun test(): ProxyValidationResult = withContext(Dispatchers.IO) {
        try {
            val req = baseRequest("/version").build()
            val resp = controllerClient.newCall(req).execute()
            resp.use {
                if (!it.isSuccessful) return@withContext ProxyValidationResult.Error("Controller responded ${it.code}")
            }
            ProxyValidationResult.Success(
                name = values["name"]?.ifBlank { null } ?: controllerUrl,
                fields = mapOf(
                    "url" to controllerUrl,
                    "secret" to secret,
                    "name" to (values["name"] ?: ""),
                ),
            )
        } catch (e: Exception) {
            ProxyValidationResult.Error(e.message ?: "Cannot reach Clash controller")
        }
    }

    suspend fun fetchProxyLines(): List<ClashProxyLine> = withContext(Dispatchers.IO) {
        try {
            val req = baseRequest("/proxies").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() ?: return@withContext emptyList() }
            val resp = json.decodeFromString<ClashProxiesResponse>(body)
            resp.proxies.values
                .filter { it.type !in setOf("Built-in", "Direct", "Reject", "RejectDrop", "Pass", "Selector", "URLTest", "Fallback", "LoadBalance") }
                .sortedBy { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun testLatencyParallel(lines: List<ClashProxyLine>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            lines.map { line ->
                async {
                    line.name to testLatency(line.name)
                }
            }.awaitAll().toMap()
        }

    private suspend fun testLatency(proxyName: String): Long = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(proxyName, "UTF-8")
            val req = baseRequest("/proxies/$encoded/delay?timeout=5000&url=http%3A%2F%2Fwww.gstatic.com%2Fgenerate_204").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() ?: return@withContext -1L }
            json.decodeFromString<ClashDelayResponse>(body).delay.toLong()
        } catch (_: Exception) {
            -1L
        }
    }

    suspend fun setActiveProxy(proxyName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(selectorName, "UTF-8")
            val bodyJson = """{"name":"$proxyName"}"""
            val req = baseRequest("/proxies/$encoded")
                .put(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            controllerClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getActiveProxy(): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(selectorName, "UTF-8")
            val req = baseRequest("/proxies/$encoded").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() ?: return@withContext null }
            val obj = json.decodeFromString<ClashProxyLine>(body)
            obj.now.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    override val ctrlUI: (@Composable (onNavigateToEdit: () -> Unit, onDismiss: () -> Unit) -> Unit)? =
        { onNavigateToEdit, onDismiss ->
            ClashCtrlUi(
                proxyId = "",
                client = this,
                onNavigateToEdit = onNavigateToEdit,
                onDismiss = onDismiss,
            )
        }
}
