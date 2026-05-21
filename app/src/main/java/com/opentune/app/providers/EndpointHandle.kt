package com.opentune.app.providers

import com.opentune.provider.EndpointClient
import okhttp3.OkHttpClient

data class EndpointHandle(
    val client: EndpointClient,
    val httpClient: OkHttpClient?,
)
