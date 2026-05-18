package com.opentune.player.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
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
        if (engine.subtitleCtrl.handleBack()) return@BackHandler
        scope.launch { engine.release(); onExit() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvPlayerView(
            player = exo,
            modifier = Modifier.fillMaxSize(),
            onOpenMenu = { menu.open() },
            onBack = { scope.launch { engine.release(); onExit() } },
            onTransportKey = { controllerVisible = true },
            onKey = { event ->
                when {
                    menu.isOpen -> menu.onKey?.invoke(event) == true
                    engine.subtitleCtrl.isAdjustActive -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (event.keyCode == KeyEvent.KEYCODE_BACK) engine.subtitleCtrl.handleBack()
                            else engine.subtitleCtrl.adjustKey(event.keyCode)
                        }
                        true // consume both UP and DOWN while adjust is active
                    }
                    else -> false
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
        engine.subtitleCtrl.AdjustOsd()
        infoOsd.Osd()
    }
}
