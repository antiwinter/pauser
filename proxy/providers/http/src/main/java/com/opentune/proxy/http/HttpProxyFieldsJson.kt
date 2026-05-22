package com.opentune.proxy.http

import kotlinx.serialization.Serializable

@Serializable
data class HttpProxyFieldsJson(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)
