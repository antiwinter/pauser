package com.opentune.player.manager

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Unified interface for all playback managers. Each manager owns a business domain (tracks,
 * fallback, subtitles, audio, speed) and may contribute listeners, lifecycle hooks, and/or
 * menu entries to the [PlaybackSession]. The session attaches all declared listeners in its
 * [init] block and calls [onPrepare]/[onDispose] at the appropriate lifecycle points.
 */
internal interface PlaybackManager {
    /** Player listeners to attach once for the whole ExoPlayer lifetime; null if none. */
    val listeners: List<Player.Listener>? get() = null

    /** Analytics listeners to attach once for the whole ExoPlayer lifetime; null if none. */
    val analyticsListeners: List<AnalyticsListener>? get() = null

    /** Called in [PlaybackSession.prepare] to reset per-entry state. */
    fun onPrepare() {}

    /** Called in [PlaybackSession.stopInternal] for cleanup. */
    fun onDispose() {}

    /** Menu entries contributed by this manager to the player settings menu. */
    val menuEntries: List<PlayerMenuEntry> get() = emptyList()
}

/**
 * A single node in the player menu tree.
 *
 * Leaf nodes have an empty [children] list. Calling [onSelect] on a leaf triggers the action and
 * closes the menu. [isSelected] drives a persistent "active" indicator (●) independent of the
 * DPAD cursor.
 */
data class PlayerMenuEntry(
    val label: @Composable () -> String,
    val children: () -> List<PlayerMenuEntry>,
    val isSelected: @Composable () -> Boolean = { false },
    val onSelect: () -> Unit = {},
    val keepMenuOpen: Boolean = false,
)
