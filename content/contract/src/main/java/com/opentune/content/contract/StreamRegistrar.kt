package com.opentune.content.contract

interface StreamRegistrar {
    fun registerStream(instance: EndpointClient, itemRef: String): String
    fun revokeToken(url: String)
}

object StreamRegistrarHolder {
    @Volatile private var instance: StreamRegistrar? = null
    fun set(registrar: StreamRegistrar) { instance = registrar }
    fun get(): StreamRegistrar = requireNotNull(instance) { "StreamRegistrar not initialized" }
}
