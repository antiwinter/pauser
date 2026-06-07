package com.opentune.content.ui.catalog

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.player.ui.configurePlayerViewDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A composable that renders a prepared ExoPlayer on a TV surface.
 * This is a lightweight wrapper around PlayerView that accepts an
 * already-prepared ExoPlayer from PlayerController.
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
    var isBuffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var hasError by remember { mutableStateOf<String?>(null) }

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

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                hasError = error.message ?: "Playback error"
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
        exoPlayer.playWhenReady = true
    }

    BackHandler {
        exoPlayer.pause()
        onBack()
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Render the video surface using Media3 PlayerView directly
        AndroidView<PlayerView>(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configurePlayerViewDefaults(this)
                }
            },
            update = { view ->
                view.setPlayer(exoPlayer)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering indicator
        if (isBuffering && !isPlaying) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
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
                    Text("Error: $err", color = Color.White)
                    Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Back")
                    }
                }
            }
        }
    }
}
