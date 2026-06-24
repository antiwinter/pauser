package com.opentune.player.ui.pad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.engine.rememberPlaybackSurface
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import kotlinx.coroutines.delay

private const val PAD_SURFACE_CONTROLLER_AUTO_HIDE_MS = 3_000L

@UnstableApi
@Composable
fun PadPlayerSurface(
    controller: PlayerSurfaceController,
    onBack: () -> Unit,
) {
    val session = controller.playbackSession
    val spec by session.currentSpecFlow.collectAsState()
    val specValue = spec ?: return

    PadPlayerSurfaceContent(
        controller = controller,
        spec = specValue,
        onBack = onBack,
    )
}

@UnstableApi
@Composable
private fun PadPlayerSurfaceContent(
    controller: PlayerSurfaceController,
    spec: com.opentune.player.PlaybackSpec,
    onBack: () -> Unit,
) {
    val session = controller.playbackSession
    val engine = rememberPlaybackSurface(
        spec = spec,
        session = session,
    )
    PlaybackHostEffects(engine.exo)

    val exo = engine.exo

    var controllerState by remember { mutableStateOf(0) }
    var position by remember { mutableLongStateOf(exo.currentPosition) }
    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) { position = newPosition.positionMs }

            override fun onPlaybackStateChanged(state: Int) {
                position = exo.currentPosition
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                position = exo.currentPosition
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPaused = !playWhenReady
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            delay(1_000)
        }
    }

    LaunchedEffect(controllerState) {
        if (controllerState != 0) {
            delay(PAD_SURFACE_CONTROLLER_AUTO_HIDE_MS)
            controllerState = 0
        }
    }

    BackHandler { engine.leaveSurface(); onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { controllerState = if (controllerState != 0) 0 else 1 }
            },
    ) {
        PadPlayerView(
            player = exo,
            session = controller.playbackSession,
        )

        AnimatedVisibility(
            visible = controllerState != 0 || isBuffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlaybackControllerBar(
                position = position,
                buffered = exo.bufferedPosition,
                duration = exo.duration.coerceAtLeast(0L),
                isPlaying = !isPaused,
                onPlayPause = {
                    isPaused = !isPaused
                    controllerState++
                },
            )
        }

        AnimatedVisibility(
            visible = isBuffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = Color.White,
            )
        }
    }
}
