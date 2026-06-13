package com.opentune.server.debug

import kotlinx.coroutines.channels.Channel

/**
 * Global singleton channel for navigation commands issued externally (e.g. from the debug HTTP API).
 * The app's NavHost collects commands from this channel and calls nav.navigate().
 */
object NavigationBridge {
    val commands = Channel<NavCommand>(Channel.BUFFERED)
}

sealed class NavCommand {
    object Home : NavCommand()
    data class Browse(val endpointId: String, val location: String?) : NavCommand()
    data class Detail(val endpointId: String, val itemRef: String) : NavCommand()
    data class Player(val endpointId: String, val itemRef: String, val startMs: Long = 0) : NavCommand()
    data class Image(val endpointId: String, val itemRef: String) : NavCommand()
    data class Search(val endpointId: String, val scopeLocation: String) : NavCommand()
}

/** Protocol prefix embedded in endpointId (`"${protocol}_${hash}"`). */
fun protocolFromEndpointId(endpointId: String): String =
    endpointId.substringBeforeLast('_')
