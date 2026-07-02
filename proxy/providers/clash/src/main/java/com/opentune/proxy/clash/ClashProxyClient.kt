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

    /** Proxy port resolved from Clash controller API (/configs). Null until fetched. */
    private var resolvedProxyPort: Int? = null

    private val host: String = runCatching {
        java.net.URL(controllerUrl).host
    }.getOrDefault("127.0.0.1")

    private fun baseRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$controllerUrl$path")
            .apply { if (secret.isNotBlank()) header("Authorization", "Bearer $secret") }

    /**
     * Fetch the proxy port from the Clash controller API (/configs).
     * Prefer mixedPort, then port (HTTP proxy), then socksPort.
     * Falls back to 7890 if nothing is configured (Clash defaults).
     */
    private suspend fun resolveProxyPort(): Int = withContext(Dispatchers.IO) {
        resolvedProxyPort ?: try {
            val req = baseRequest("/configs").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() }
            val configs = body?.let { json.decodeFromString<ClashConfigsResponse>(it) }
            val port = configs?.mixedPort
                ?: configs?.port
                ?: configs?.socksPort
                ?: 7890
            resolvedProxyPort = port
            port
        } catch (_: Exception) {
            7890
        }
    }

    override fun getHttpClient(): OkHttpClient = cached

    override val proxy: Proxy
        get() = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, resolvedProxyPort ?: 7890))

    // One shared OkHttpClient (and ConnectionPool) per proxy config — stream and JS engine
    // both call getHttpClient(), so they must share a single pool through the proxy.
    // resolvedProxyPort is populated by test(), which always runs before playback.
    private val cached: OkHttpClient by lazy {
        val proxyPort = resolvedProxyPort ?: 7890
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, proxyPort)))
            .build()
    }

    override fun getConfig(): Map<String, String> = buildMap {
        put("type", "clash")
        put("host", host)
        put("port", (resolvedProxyPort ?: 7890).toString())
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
            resolveProxyPort() // cache the proxy port for later use
            ProxyValidationResult.Success(
                name = values["name"]?.ifBlank { null } ?: controllerUrl,
                fields = mapOf(
                    "url" to controllerUrl,
                    "secret" to secret,
                    "port" to (resolvedProxyPort ?: 7890).toString(),
                    "name" to (values["name"] ?: ""),
                ),
            )
        } catch (e: Exception) {
            ProxyValidationResult.Error(e.message ?: "Cannot reach Clash controller")
        }
    }

    /**
     * Download subscription YAML from [subscriptionUrl] and apply it to the
     * Clash controller via PUT /configs (mihomo/Clash Meta payload format).
     *
     * Returns true if the controller accepted the new config, false on any error.
     */
    suspend fun refreshSubscription(subscriptionUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Download subscription YAML with clash UA (most services return Clash YAML this way)
            val subClient = OkHttpClient.Builder().build()
            val yamlContent = downloadSubscriptionYaml(subscriptionUrl, subClient)
                ?: return@withContext false

            // 2. PUT /configs with raw YAML payload (mihomo expects raw YAML, not base64)
            val reloadBody = json.encodeToString(
                ClashConfigsReloadRequest.serializer(),
                ClashConfigsReloadRequest(path = "", payload = yamlContent),
            )
            val reloadReq = baseRequest("/configs")
                .put(reloadBody.toRequestBody("application/json".toMediaType()))
                .build()
            controllerClient.newCall(reloadReq).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Download subscription content, attempting to get Clash YAML format.
     * First tries with clash UA; if content is not Clash YAML, retries with &flag=clash.
     */
    private fun downloadSubscriptionYaml(url: String, client: OkHttpClient): String? {
        // Try with clash.meta UA first — most subscription services return Clash YAML
        val req1 = Request.Builder().url(url).header("User-Agent", "clash.meta").build()
        val body1 = client.newCall(req1).execute().use { it.body?.string() }
        if (body1 != null && isClashYaml(body1)) return body1

        // Fallback: append &flag=clash parameter
        val flagUrl = if (url.contains("?")) "$url&flag=clash" else "$url?flag=clash"
        val req2 = Request.Builder().url(flagUrl).header("User-Agent", "clash.meta").build()
        val body2 = client.newCall(req2).execute().use { it.body?.string() }
        if (body2 != null && isClashYaml(body2)) return body2

        // Last resort: return whatever we got (might still work with some controllers)
        return body1
    }

    /** Quick heuristic: does the content look like a Clash YAML config? */
    private fun isClashYaml(content: String): Boolean =
        content.lines().any { line -> line.startsWith("proxies:") || line.startsWith("proxy-groups:") || line.startsWith("mixed-port:") }

    private val skipTypes = setOf("Built-in", "Direct", "Reject", "RejectDrop", "Pass", "Selector", "URLTest", "Fallback", "LoadBalance", "Compatible")

    /**
     * Derive a short label for the currently loaded config,
     * e.g. "三毛机场 · 35 lines" or just "35 lines".
     */
    suspend fun fetchSubscriptionLabel(): String? = withContext(Dispatchers.IO) {
        try {
            val req = baseRequest("/proxies").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() ?: return@withContext null }
            val resp = json.decodeFromString<ClashProxiesResponse>(body)
            val proxyCount = resp.proxies.values.count { it.type !in skipTypes }
            if (proxyCount == 0) return@withContext null
            val groupName = resp.proxies.values
                .firstOrNull { it.type == "Selector" && it.name != "GLOBAL" }
                ?.name
            if (groupName != null) "$groupName · $proxyCount lines" else "$proxyCount lines"
        } catch (_: Exception) { null }
    }

    suspend fun fetchProxyLines(): List<ClashProxyLine> = withContext(Dispatchers.IO) {
        try {
            val req = baseRequest("/proxies").build()
            val body = controllerClient.newCall(req).execute().use { it.body?.string() ?: return@withContext emptyList() }
            val resp = json.decodeFromString<ClashProxiesResponse>(body)
            resp.proxies.values
                .filter { it.type !in skipTypes }
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

    suspend fun enableLan(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = baseRequest("/configs")
                .patch("""{"allow-lan":true}""".toRequestBody("application/json".toMediaType()))
                .build()
            controllerClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
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

    override val ctrlUI: (@Composable (onNavigateToEdit: () -> Unit, onBack: () -> Unit) -> Unit)? =
        { onNavigateToEdit, onBack ->
            ClashCtrlUi(
                proxyId = "",
                client = this,
                onNavigateToEdit = onNavigateToEdit,
                onBack = onBack,
            )
        }
}
