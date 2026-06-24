package com.opentune.player.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.player.PlayerSurfaceController
import com.opentune.player.ui.InfoOverlay
import com.opentune.player.ui.MenuOverlay
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import com.opentune.player.manager.subtitle.SubtitleAdjustOverlay
import com.opentune.player.ui.rememberInfoOverlayState
import com.opentune.player.ui.rememberMenuOverlayState
import com.opentune.player.engine.rememberPlaybackSurface
import kotlinx.coroutines.delay

private const val TV_SURFACE_CONTROLLER_AUTO_HIDE_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun TvPlayerSurface(
    controller: PlayerSurfaceController,
    onBack: () -> Unit,
) {
    val session = controller.playbackSession
    val spec by session.currentSpecFlow.collectAsState()
    val specValue = spec ?: run {
        PlayerLoadingOverlay(onBack = onBack)
        return
    }

    TvPlayerSurfaceContent(
        controller = controller,
        spec = specValue,
        onBack = onBack,
    )
}

@Composable
private fun PlayerLoadingOverlay(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Loading spec...",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    BackHandler { onBack() }
}

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
private fun TvPlayerSurfaceContent(
    controller: PlayerSurfaceController,
    spec: com.opentune.player.PlaybackSpec,
    onBack: () -> Unit,
) {
    val hasNextVideo by controller.hasNextVideoFlow.collectAsState()
    val displayInfo by controller.displayInfoFlow.collectAsState()
    val session = controller.playbackSession
    val surface = rememberPlaybackSurface(
        spec = spec,
        session = session,
    )
    PlaybackHostEffects(surface.exo)

    val exo = surface.exo

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

    androidx.compose.runtime.LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            delay(1_000)
        }
    }

    androidx.compose.runtime.LaunchedEffect(controllerState) {
        if (controllerState != 0) {
            delay(TV_SURFACE_CONTROLLER_AUTO_HIDE_MS)
            controllerState = 0
        }
    }

    val sourceManager by controller.sourceManagerFlow.collectAsState()
    val menu = rememberMenuOverlayState(
        *buildList {
            addAll(surface.menuEntries)
            if (sourceManager != null && sourceManager!!.sourceCount > 1) {
                add(sourceManager!!.menuEntry)
            }
        }.toTypedArray()
    )

    val infoOverlay = rememberInfoOverlayState(
        instanceKey = spec.sources[spec.state.sourceIndex].url,
        displayInfo = displayInfo,
        session = session,
        spec = spec,
        bandwidthMbps = surface.bandwidthMbps,
    )

    if (controllerState != 0) infoOverlay.show() else infoOverlay.hide()

    BackHandler {
        when {
            menu.isOpen -> menu.back()
            surface.subtitleManager.adjust.isActive -> surface.subtitleManager.adjust.confirm()
            controllerState != 0 -> controllerState = 0
            else -> { surface.leaveSurface(); onBack() }
        }
    }

    var menuConsumedDown by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        TvPlayerView(
            player = exo,
            session = session,
            modifier = Modifier.fillMaxSize(),
            onOpenMenu = { menu.open() },
            onBack = {
                when {
                    controllerState != 0 -> controllerState = 0
                    else -> { surface.leaveSurface(); onBack() }
                }
            },
            onTransportKey = { isResume ->
                if (!isResume || controllerState != 0) controllerState++
            },
            onKey = { event ->
                when {
                    menu.isOpen -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            menuConsumedDown = true
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> menu.navigateUp()
                                KeyEvent.KEYCODE_DPAD_DOWN -> menu.navigateDown()
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> menu.confirm()
                                KeyEvent.KEYCODE_BACK,
                                KeyEvent.KEYCODE_DPAD_LEFT -> menu.back()
                            }
                        }
                        true
                    }
                    menuConsumedDown && event.action == KeyEvent.ACTION_UP -> {
                        menuConsumedDown = false
                        true
                    }
                    surface.subtitleManager.adjust.isActive -> {
                        surface.subtitleManager.adjust.onKeyEvent(event)
                        true
                    }
                    else -> { menuConsumedDown = false; false }
                }
            },
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
            Text(
                text = "buffering...",
                modifier = Modifier
                    .background(Color(0x88000000), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        AnimatedVisibility(
            visible = controllerState != 0 && hasNextVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 72.dp),
        ) {
            androidx.tv.material3.Button(onClick = { controller.requestNextVideo() }) {
                Text("Next")
            }
        }

        MenuOverlay(menu)
        SubtitleAdjustOverlay(surface.subtitleManager.adjust)
        InfoOverlay(infoOverlay)
    }
}
