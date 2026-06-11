package com.opentune.player.manager

import androidx.compose.runtime.Composable

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
)
