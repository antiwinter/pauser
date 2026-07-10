package com.insomnia.content.contract

import java.util.concurrent.ConcurrentHashMap

/** Port the embedded stream-relay/debug server listens on. */
const val SERVER_PORT = 7920

/** Sink a relay pump writes to; lets recipes pump suspend-natively without depending on Ktor. */
fun interface ByteSink {
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
}

/**
 * HTTP response streamed via [pump]. The pump owns the source lifecycle (close in `finally`).
 * For a range response set status=206, `Content-Range` in [headers], slice length in [length].
 */
data class StreamRelayResult(
    val status: Int,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val length: Long? = null,
    val pump: suspend (ByteSink) -> Unit,
)

/** Backs a `/{token}` route; the server calls [serve] and pumps the result. */
interface StreamRelayRecipe {
    suspend fun serve(params: Map<String, String>): StreamRelayResult?
}

/** Process-wide token → recipe map; the `/{token}` route looks up here. */
object StreamRelayRegistry {
    private val entries = ConcurrentHashMap<String, StreamRelayRecipe>()

    // Idempotent on same recipe instance so endpoint re-add doesn't throw; throws on a
    // different-instance collision (two providers trying to claim one stable token).
    fun register(token: String, recipe: StreamRelayRecipe): String {
        val previous = entries.putIfAbsent(token, recipe)
        if (previous != null && previous !== recipe) {
            error("StreamRelayRegistry: token '$token' is already registered with a different recipe")
        }
        return token
    }

    fun get(token: String): StreamRelayRecipe? = entries[token]

    fun remove(token: String) { entries.remove(token) }
}
