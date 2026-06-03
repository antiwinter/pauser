package com.opentune.player.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.player.R
import com.opentune.player.controller.rememberMenuOverlay
import com.opentune.player.LocalPlaybackStorageContext
import com.opentune.player.engine.TrackInfo
import com.opentune.player.engine.rememberPlaybackEngine
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import com.opentune.player.PlaybackSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TV_CONTROLLER_AUTO_HIDE_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun TvPlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
) {
    val storageCtx = LocalPlaybackStorageContext.current
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

    // Auto-hide after 5s on TV when controller is shown.
    LaunchedEffect(controllerState) {
        if (controllerState != 0) {
            delay(TV_CONTROLLER_AUTO_HIDE_MS)
            controllerState = 0
        }
    }

    val menu = rememberMenuOverlay(
        engine.subtitleCtrl.menuEntry,
        engine.subtitleCtrl.adjustMenuEntry,
        engine.audioCtrl.menuEntry,
        engine.speedCtrl.menuEntry,
    )

    val trackInfo: TrackInfo by engine.trackInfo
    val infoOsd = rememberInfoOsd(
        instanceKey = storageCtx.entryStateKey,
        spec = spec,
        exo = exo,
        videoMime = trackInfo.videoMime,
        videoDecoderStatus = trackInfo.videoDecoderStatus,
        audioMime = trackInfo.audioMime,
        audioDecoderStatus = trackInfo.audioDecoderStatus,
        mbpsState = engine.bandwidthMbps,
    )

    // Keep InfoOsd in sync with controller visibility.
    if (controllerState != 0) infoOsd.show() else infoOsd.hide()

    BackHandler {
        when {
            menu.isOpen -> menu.back()
            engine.subtitleCtrl.isAdjustActive -> engine.subtitleCtrl.confirmAdjust()
            controllerState != 0 -> controllerState = 0
            else -> scope.launch { engine.release(); onExit() }
        }
    }

    // Tracks whether the menu handled the last ACTION_DOWN so the paired ACTION_UP
    // is consumed even if the menu already closed by then.
    var menuConsumedDown by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        TvPlayerView(
            player = exo,
            modifier = Modifier.fillMaxSize(),
            onOpenMenu = { menu.open() },
            onBack = {
                when {
                    controllerState != 0 -> controllerState = 0
                    else -> scope.launch { engine.release(); onExit() }
                }
            },
            onTransportKey = { isResume ->
                // Rule 2: resume (pause→play) only refreshes the timer if the bar is already visible.
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
                    engine.subtitleCtrl.isAdjustActive -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> engine.subtitleCtrl.adjustOffsetUp()
                                KeyEvent.KEYCODE_DPAD_DOWN -> engine.subtitleCtrl.adjustOffsetDown()
                                KeyEvent.KEYCODE_DPAD_LEFT -> engine.subtitleCtrl.adjustScaleDown()
                                KeyEvent.KEYCODE_DPAD_RIGHT -> engine.subtitleCtrl.adjustScaleUp()
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER,
                                KeyEvent.KEYCODE_BACK -> engine.subtitleCtrl.confirmAdjust()
                            }
                        }
                        true
                    }
                    else -> { menuConsumedDown = false; false }
                }
            },
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

        menu.Overlay()
        SubtitleAdjustOsd(
            isActive = engine.subtitleCtrl.isAdjustActive,
            translationYPx = engine.subtitleCtrl.translationYPx,
            sizeScale = engine.subtitleCtrl.sizeScale,
        )
        infoOsd.Osd()
    }
}

@Composable
private fun SubtitleAdjustOsd(
    isActive: Boolean,
    translationYPx: Float,
    sizeScale: Float,
) {
    if (!isActive) return
    // Convert pixel offset to dp so the preview bar matches the subtitle view's bottom margin.
    val previewBottomDp = with(LocalDensity.current) { translationYPx.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        // Live preview bar — moves and scales exactly as the subtitle rendering will.
        Text(
            text = stringResource(R.string.subtitle_adjust_sample),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Keep the preview above the hint strip; add 48dp clearance.
                .padding(bottom = (previewBottomDp + 48.dp).coerceAtLeast(48.dp))
                .graphicsLayer { scaleX = sizeScale; scaleY = sizeScale }
                .background(Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 28.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 20.sp,
        )
        // Fixed key-binding hint at the very bottom.
        Text(
            text = stringResource(R.string.subtitle_adjust_hint),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color(0xFFAAAAAA),
            fontSize = 13.sp,
        )
    }
}
