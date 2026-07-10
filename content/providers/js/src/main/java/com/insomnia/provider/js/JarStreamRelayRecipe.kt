package com.insomnia.provider.js

import com.insomnia.content.contract.StreamRelayRecipe
import com.insomnia.content.contract.StreamRelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Stream-relay recipe that serves a relay route by reflectively invoking a (typically static)
 * method on a class inside a loaded JAR.
 */
class JarStreamRelayRecipe private constructor(
    private val jarLoader: JarLoader,
    private val cls: String,
    private val method: String,
) : StreamRelayRecipe {
    override suspend fun serve(params: Map<String, String>): StreamRelayResult? =
        withContext(Dispatchers.IO) { jarLoader.invokeStreaming(cls, method, params) }

    companion object {
        // Process-wide identity per (cls, method) so re-registration from a re-evaluated JS
        // bundle returns the same instance (StreamRelayRegistry treats that as idempotent).
        private val byKey = ConcurrentHashMap<String, JarStreamRelayRecipe>()
        fun get(jarLoader: JarLoader, cls: String, method: String): JarStreamRelayRecipe =
            byKey.getOrPut("$cls.$method") { JarStreamRelayRecipe(jarLoader, cls, method) }
    }
}
