package com.opentune.player.ui.pad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import com.opentune.player.engine.rememberPlaybackEngine
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import com.opentune.player.PlaybackSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAD_CONTROLLER_AUTO_HIDE_MS = 3_000L

@UnstableApi
@Composable
fun PadPlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
) {
    val engine = rememberPlaybackEngine(
        spec = spec,
        startMs = startMs,
        initialSubtitleTrackId = initialSubtitleTrackId,
        initialAudioTrackId = initialAudioTrackId,
        initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
        initialSubtitleSizeScale = initialSubtitleSizeScale,
    )
    PlaybackHostEffects(engine.exo)

    val scope = rememberCoroutineScope()
    val exo = engine.exo

    /** 0 = hidden, >0 = visible. Incrementing resets the auto-hide timer. */
    var controllerState by remember { mutableStateOf(0) }
    var position by remember { mutableLongStateOf(exo.currentPosition) }
    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    // Event-driven state updates from ExoPlayer.
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                position = newPosition.positionMs
            }

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

    // Fast tick for smooth progress bar animation during steady playback.
    // Not gating any action feedback — just drives the visual tick.
    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            delay(1_000)
        }
    }

    // Auto-hide after 3s on Pad.
    LaunchedEffect(controllerState) {
        if (controllerState != 0) {
            delay(PAD_CONTROLLER_AUTO_HIDE_MS)
            controllerState = 0
        }
    }

    BackHandler { scope.launch { engine.release(); onExit() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { controllerState = if (controllerState != 0) 0 else 1 }
            },
    ) {
        PadPlayerView(
            player = exo,
            subtitleTranslationYPx = engine.subtitleCtrl.translationYPx,
            subtitleSizeScale = engine.subtitleCtrl.sizeScale,
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

        // Centered spinner — visible during buffering, regardless of controller bar visibility.
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
