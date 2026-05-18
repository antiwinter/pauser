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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.player.R
import com.opentune.player.controller.rememberMenuOverlay
import com.opentune.player.engine.TrackInfo
import com.opentune.player.engine.rememberPlaybackEngine
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.AppConfigStore
import com.opentune.storage.MediaStateKey
import com.opentune.storage.UserMediaStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TV_CONTROLLER_AUTO_HIDE_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun TvPlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    mediaStateStore: UserMediaStateStore,
    mediaStateKey: MediaStateKey,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialAudioTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
    appConfigStore: AppConfigStore? = null,
) {
    val engine = rememberPlaybackEngine(
        spec = spec,
        startMs = startMs,
        mediaStateStore = mediaStateStore,
        mediaStateKey = mediaStateKey,
        appConfigStore = appConfigStore,
        initialSubtitleTrackId = initialSubtitleTrackId,
        initialAudioTrackId = initialAudioTrackId,
        initialSubtitleOffsetFraction = initialSubtitleOffsetFraction,
        initialSubtitleSizeScale = initialSubtitleSizeScale,
    )
    PlaybackHostEffects(engine.exo)

    val scope = rememberCoroutineScope()
    val exo = engine.exo

    var controllerVisible by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }

    // Poll position every 500ms for the controller bar display.
    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            delay(500)
        }
    }

    // Auto-hide after 5s on TV when controller is shown.
    LaunchedEffect(controllerVisible) {
        if (controllerVisible) {
            delay(TV_CONTROLLER_AUTO_HIDE_MS)
            controllerVisible = false
        }
    }

    val menu = rememberMenuOverlay(
        engine.subtitleCtrl.menuEntry,
        engine.audioCtrl.menuEntry,
        engine.speedCtrl.menuEntry,
    )

    val trackInfo: TrackInfo by engine.trackInfo
    val infoOsd = rememberInfoOsd(
        instanceKey = mediaStateKey,
        spec = spec,
        videoMime = trackInfo.videoMime,
        videoDecoderName = trackInfo.videoDecoderName,
        audioMime = trackInfo.audioMime,
        audioDecoderName = trackInfo.audioDecoderName,
        mbpsState = engine.bandwidthMbps,
    )

    // Keep InfoOsd in sync with controller visibility.
    if (controllerVisible) infoOsd.show() else infoOsd.hide()

    BackHandler {
        when {
            menu.isOpen -> menu.back()
            engine.subtitleCtrl.isAdjustActive -> engine.subtitleCtrl.confirmAdjust()
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
            onBack = { scope.launch { engine.release(); onExit() } },
            onTransportKey = { controllerVisible = true },
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
            visible = controllerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlaybackControllerBar(
                position = position,
                buffered = exo.bufferedPosition,
                duration = exo.duration.coerceAtLeast(0L),
                isPlaying = exo.isPlaying,
                onPlayPause = {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    controllerVisible = true
                },
            )
        }

        menu.Overlay()
        SubtitleAdjustOsd(isActive = engine.subtitleCtrl.isAdjustActive)
        infoOsd.Osd()
    }
}

@Composable
private fun SubtitleAdjustOsd(isActive: Boolean) {
    if (!isActive) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = stringResource(R.string.subtitle_adjust_hint),
            modifier = Modifier
                .padding(bottom = 72.dp)
                .background(Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}
