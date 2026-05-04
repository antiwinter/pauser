package com.opentune.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.playback.api.OpenTunePlaybackHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun OpenTunePlayerScreen(
    exoPlayer: ExoPlayer,
    hooks: OpenTunePlaybackHooks,
    startPositionMs: Long,
    onExit: () -> Unit,
    topBanner: (@Composable BoxScope.() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var speed by remember { mutableFloatStateOf(1f) }
    val speedState = rememberUpdatedState(speed)
    val hooksState = rememberUpdatedState(hooks)
    val onExitState = rememberUpdatedState(onExit)
    val released = remember(exoPlayer) { AtomicBoolean(false) }

    suspend fun shutdown(userInitiatedExit: Boolean) {
        if (!released.compareAndSet(false, true)) {
            if (userInitiatedExit) {
                withContext(Dispatchers.Main) { onExitState.value() }
            }
            return
        }
        val pos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
        hooksState.value.onStop(pos)
        withContext(Dispatchers.Main) { exoPlayer.release() }
        if (userInitiatedExit) {
            withContext(Dispatchers.Main) { onExitState.value() }
        }
    }

    LaunchedEffect(exoPlayer, hooks, startPositionMs) {
        while (exoPlayer.playbackState != Player.STATE_READY && isActive) {
            delay(32)
        }
        if (!isActive) return@LaunchedEffect
        if (startPositionMs > 0) {
            withContext(Dispatchers.Main) { exoPlayer.seekTo(startPositionMs) }
            var n = 0
            while (isActive && n++ < 200) {
                val cur = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
                if (abs(cur - startPositionMs) < 1500) break
                delay(32)
            }
        }
        if (!isActive) return@LaunchedEffect
        if (released.get()) return@LaunchedEffect
        val pos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
        hooks.onPlaybackReady(pos, speedState.value)
    }

    LaunchedEffect(exoPlayer, hooks) {
        val interval = hooks.progressIntervalMs()
        if (interval <= 0L) return@LaunchedEffect
        while (isActive) {
            delay(interval)
            if (!exoPlayer.isPlaying) continue
            if (released.get()) break
            val pos = exoPlayer.currentPosition
            hooksState.value.onProgressTick(pos, speedState.value)
        }
    }

    LaunchedEffect(exoPlayer, speed) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    BackHandler {
        scope.launch { shutdown(userInitiatedExit = true) }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            runBlocking { shutdown(userInitiatedExit = false) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            OpenTunePlayerView(player = exoPlayer, modifier = Modifier.fillMaxSize())
            topBanner?.invoke(this@Box)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { s ->
                    Button(onClick = { speed = s }) {
                        Text("${s}x")
                    }
                }
                Button(
                    onClick = { scope.launch { shutdown(userInitiatedExit = true) } },
                ) {
                    Text("Exit")
                }
            }
        }
    }
}
