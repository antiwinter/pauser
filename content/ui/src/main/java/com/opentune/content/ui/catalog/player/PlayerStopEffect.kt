package com.opentune.content.ui.catalog.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Collect [PlayerController.stopEvents] while this composable is in the tree. */
@Composable
fun PlayerStopEffect(
    playerController: PlayerController?,
    onStop: () -> Unit,
) {
    LaunchedEffect(playerController) {
        val controller = playerController ?: return@LaunchedEffect
        controller.stopEvents.collect { onStop() }
    }
}
