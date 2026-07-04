package com.insomnia.server

import com.insomnia.content.contract.SERVER_PORT
import com.insomnia.server.debug.installDebugRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import timber.log.Timber

/**
 * Embedded HTTP server for the app's lifetime. Binds to `0.0.0.0` on [SERVER_PORT]. Composes
 * [StreamRelayRoute] (`/relay/{token}` recipe pass-through) and, when [appContext] is non-null,
 * the debug + gen-art routes.
 */
class InsomniaServer(
    private val appContext: AppContext? = null,
) {
    private val streamRelayRoute = StreamRelayRoute()

    private val engine = embeddedServer(CIO, host = "0.0.0.0", port = SERVER_PORT) {
        with(streamRelayRoute) { installRoutes() }
        appContext?.let { installGenartRoutes(it) }
        appContext?.let { installDebugRoutes(it) }
    }

    fun start() {
        engine.start(wait = false)
        Timber.i("InsomniaServer started on port $SERVER_PORT")
    }

    fun stop() {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
}
