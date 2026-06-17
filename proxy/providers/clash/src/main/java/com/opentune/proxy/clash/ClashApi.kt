package com.opentune.proxy.clash

import kotlinx.serialization.Serializable

@Serializable
data class ClashProxyLine(
    val name: String,
    val type: String,
    val history: List<ClashDelay> = emptyList(),
    val now: String = "",
) {
    val latencyMs: Long?
        get() = history.lastOrNull()?.delay?.toLong()
}

@Serializable
data class ClashDelay(
    val time: String = "",
    val delay: Int = 0,
)

@Serializable
data class ClashProxiesResponse(
    val proxies: Map<String, ClashProxyLine> = emptyMap(),
)

@Serializable
data class ClashDelayResponse(
    val delay: Int = 0,
)

@Serializable
data class ClashConfigsResponse(
    val mixedPort: Int? = null,
    val port: Int? = null,
    val socksPort: Int? = null,
    val path: String? = null,
)

@Serializable
data class ClashConfigsReloadRequest(
    val path: String = "",
    val payload: String = "",
)
