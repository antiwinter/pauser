package com.opentune.content.ui.catalog

import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.configurePlayerViewDefaults
import com.opentune.player.ui.tv.OpenTuneTvPlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOG_TAG = "OT_PlayerSurface"

/**
 * A composable that renders a prepared ExoPlayer on a TV surface with full DPAD/OSD support.
 * Uses [OpenTuneTvPlayerView] for key handling (play/pause, seek, menu, OSD).
 */
@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerSurface(
    exoPlayer: ExoPlayer,
    startMs: Long = 0L,
    onBack: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var hasError by remember { mutableStateOf<String?>(null) }

    // OSD controller state: 0 = hidden, >0 = visible
    var controllerState by remember { mutableIntStateOf(0) }

    // Sync position and state from ExoPlayer
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                position = exoPlayer.currentPosition
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                position = exoPlayer.currentPosition
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPaused = !playWhenReady
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                hasError = error.message ?: "Playback error"
                Log.e(LOG_TAG, "onPlayerError: ${error.message}")
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Position tick during playback
    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            position = exoPlayer.currentPosition
            delay(1_000)
        }
    }

    // Handle media end
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    scope.launch { onBack() }
                }
            }
        }
        exoPlayer.addListener(listener)
    }

    // Start playback
    LaunchedEffect(exoPlayer) {
        Log.d(LOG_TAG, "setting playWhenReady=true")
        exoPlayer.playWhenReady = true
    }

    // Auto-hide OSD after 5s
    LaunchedEffect(controllerState) {
        if (controllerState != 0) {
            delay(5_000)
            controllerState = 0
        }
    }

    BackHandler {
        exoPlayer.pause()
        onBack()
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Video surface with DPAD key handling
        AndroidView<OpenTuneTvPlayerView>(
            factory = { context ->
                OpenTuneTvPlayerView(context).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configurePlayerViewDefaults(this)
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
                view.onBack = {
                    when {
                        controllerState != 0 -> controllerState = 0
                        else -> onBack()
                    }
                }
                view.onTransportKey = { isResume ->
                    // Resume only refreshes timer if bar already visible
                    if (!isResume || controllerState != 0) controllerState++
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // OSD controller bar
        AnimatedVisibility(
            visible = controllerState != 0 || isBuffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlaybackControllerBar(
                position = position,
                buffered = exoPlayer.bufferedPosition,
                duration = exoPlayer.duration.coerceAtLeast(0L),
                isPlaying = !isPaused,
                onPlayPause = {
                    isPaused = !isPaused
                    controllerState++
                },
            )
        }

        // Buffering spinner
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

        // Error state
        hasError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    androidx.tv.material3.Text("Error: $err", color = Color.White)
                    androidx.tv.material3.Button(
                        onClick = onBack,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        androidx.tv.material3.Text("Back")
                    }
                }
            }
        }
    }
}
