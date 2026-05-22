package com.opentune.server

import android.util.Log
import com.opentune.content.contract.StreamRegistrar
import com.opentune.server.debug.installDebugRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

const val SERVER_PORT = 7920
private const val LOG_TAG = "OpenTuneServer"

/**
 * Embedded HTTP server for the app's lifetime. Binds to all interfaces on fixed port [SERVER_PORT].
 *
 * Internally owns and composes all route modules:
 * - [StreamProxy] — `/stream/{token}` byte-streaming for providers
 * - debug routes  — provider/catalog/navigate API, installed only when [debugContext] is non-null
 *
 * Implements [StreamRegistrar] by delegating to [StreamProxy]; callers only interact with this
 * single class.
 */
class OpenTuneServer private constructor(
    private val streamProxy: StreamProxy,
    private val appContext: AppContext? = null,
) : StreamRegistrar by streamProxy {

    constructor(appContext: AppContext? = null) : this(StreamProxy(), appContext)

    private val engine = embeddedServer(CIO, host = "0.0.0.0", port = SERVER_PORT) {
        with(streamProxy) { installRoutes() }
        appContext?.let { installGenartRoutes(it) }
        appContext?.let { installDebugRoutes(it) }
    }

    fun start() {
        engine.start(wait = false)
        Log.i(LOG_TAG, "OpenTuneServer started on port $SERVER_PORT")
    }

    fun stop() {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
}
