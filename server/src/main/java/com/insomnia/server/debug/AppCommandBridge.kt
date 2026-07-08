package com.insomnia.server.debug

import kotlinx.coroutines.channels.Channel

/**
 * Global singleton channel for app commands issued externally (e.g. from the debug HTTP API).
 * The app's NavHost collects commands from this channel and dispatches them — most drive
 * nav.navigate(...), but non-navigation controls (e.g. Seek) are admitted too, since the
 * bridge is the app's only server→UI entry point.
 */
object AppCommandBridge {
    val commands = Channel<AppCommand>(Channel.BUFFERED)
}

sealed class AppCommand {
    object Home : AppCommand()
    data class Browse(val endpointId: String, val location: String?) : AppCommand()
    data class Detail(val endpointId: String, val itemRef: String) : AppCommand()
    data class Player(val endpointId: String, val itemRef: String, val startMs: Long = 0) : AppCommand()
    data class Image(val endpointId: String, val itemRef: String) : AppCommand()
    data class Search(val endpointId: String, val scopeLocation: String) : AppCommand()
    data class Seek(val positionMs: Long? = null, val deltaMs: Long? = null) : AppCommand()
}

/** Protocol prefix embedded in endpointId (`"${protocol}_${hash}"`). */
fun protocolFromEndpointId(endpointId: String): String =
    endpointId.substringBeforeLast('_')
