package com.insomnia.app

import com.insomnia.provider.js.HostApis
import com.insomnia.provider.js.JarLoader
import com.insomnia.server.debug.JarBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements [JarBridge] by delegating to the shared [JarLoader] instance.
 * Runs on IO dispatcher since JAR loading and reflection are blocking.
 */
class JarBridgeImpl(
    private val jarLoader: JarLoader,
    private val hostApis: HostApis,
) : JarBridge {
    override suspend fun dispatch(name: String, argsJson: String): String? =
        withContext(Dispatchers.IO) {
            hostApis.handleJar(name, argsJson, jarLoader)
        }
}
