package com.opentune.provider

/**
 * App-level service that maps opaque tokens to provider streams and serves them over HTTP.
 * Providers call [registerStream] to obtain a plain `http://` URL for any byte resource;
 * the server implementation in `:app` opens the stream on-demand per incoming request.
 *
 * Set at app startup via [StreamRegistrarHolder.set]; accessed by providers at runtime.
 */
interface StreamRegistrar {
    /**
     * Registers [instance] + [itemRef] under a fresh token and returns a full HTTP URL
     * (e.g. `http://127.0.0.1:<port>/stream/<token>`).
     * The token stays valid until [revokeToken] is called with the returned URL.
     */
    fun registerStream(instance: OpenTuneProviderInstance, itemRef: String): String

    /** Revokes the token embedded in [url]. No-op if the token is unknown or already revoked. */
    fun revokeToken(url: String)
}

object StreamRegistrarHolder {
    @Volatile private var instance: StreamRegistrar? = null

    fun set(registrar: StreamRegistrar) {
        instance = registrar
    }

    fun get(): StreamRegistrar =
        requireNotNull(instance) { "StreamRegistrar not initialized — OpenTuneServer not started?" }
}
