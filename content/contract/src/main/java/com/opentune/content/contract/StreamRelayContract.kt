package com.opentune.content.contract

import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Port the embedded stream-relay / debug server listens on. Shared across modules. */
const val SERVER_PORT = 7920

/**
 * Result of a [StreamRelayRecipe.serve] call: an HTTP response to stream back to the player.
 * Shape mirrors fongmi's spider `proxy(Map)` return: `[statusCode, contentType, InputStream, headers]`.
 */
data class StreamRelayResult(
    val status: Int,
    val contentType: String?,
    val stream: InputStream,
    val headers: Map<String, String> = emptyMap(),
    val length: Long? = null,
)

/**
 * A content provider backing a `/relay/{token}` route. Implementations are registered by
 * providers (e.g. the js provider registers a reflective spider-proxy recipe). The server
 * layer is agnostic of the provider protocol — it only calls [serve] and streams [StreamRelayResult].
 */
interface StreamRelayRecipe {
    suspend fun serve(params: Map<String, String>): StreamRelayResult?
}

/**
 * Process-wide registry mapping relay tokens → recipe. Providers register recipes
 * via host APIs (engine-scoped); the Ktor `/relay/{token}` route looks them up here.
 * The relay is a pure pass-through streamer — no rules, no body rewriting.
 */
object StreamRelayRegistry {
    private val entries = ConcurrentHashMap<String, StreamRelayRecipe>()

    /** Registers [recipe], returns the token. Caller builds the public URL from [SERVER_PORT]. */
    fun register(recipe: StreamRelayRecipe): String =
        UUID.randomUUID().toString().replace("-", "").also { entries[it] = recipe }

    fun get(token: String): StreamRelayRecipe? = entries[token]

    fun remove(token: String) { entries.remove(token) }
}
