package com.insomnia.server.debug

/**
 * Thin interface so `:server` can proxy `host.jar.*` calls without depending on `:content:providers:js`.
 * Implemented in `:app` by wrapping `JarLoader` + `HostApis.handleJar`.
 */
interface JarBridge {
    /**
     * Dispatch a `host.jar.<name>` call.
     * [argsJson] is the raw JSON object passed from JS (same shape as `HostApis.handleJar`).
     * Returns a JSON string result, or null for void calls.
     */
    suspend fun dispatch(name: String, argsJson: String): String?
}
