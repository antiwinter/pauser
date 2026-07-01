package com.opentune.provider.js

import com.opentune.content.contract.StreamRelayRecipe
import com.opentune.content.contract.StreamRelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stream-relay recipe that serves a `/relay/{token}` request by reflectively invoking a (typically
 * static) method on a class inside a loaded JAR — e.g. a catvod spider's `Proxy.proxy(Map)`.
 *
 * The class/method names are supplied by the provider (TS); this class is generic and knows
 * nothing about catvod. It only depends on [JarLoader]'s reflective plumbing.
 */
class JarStreamRelayRecipe(
    private val jarLoader: JarLoader,
    private val cls: String,
    private val method: String,
) : StreamRelayRecipe {
    override suspend fun serve(params: Map<String, String>): StreamRelayResult? =
        withContext(Dispatchers.IO) { jarLoader.invokeStreaming(cls, method, params) }
}
