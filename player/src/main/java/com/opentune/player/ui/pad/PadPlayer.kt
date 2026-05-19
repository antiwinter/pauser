package com.opentune.player.ui.pad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import com.opentune.player.engine.rememberPlaybackEngine
import com.opentune.player.ui.PlaybackControllerBar
import com.opentune.player.ui.PlaybackHostEffects
import com.opentune.provider.PlaybackSpec
import com.opentune.storage.AppConfigStore
import com.opentune.storage.EntryStateKey
import com.opentune.storage.EntryStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAD_CONTROLLER_AUTO_HIDE_MS = 3_000L

@UnstableApi
@Composable
fun PadPlayer(
    spec: PlaybackSpec,
    startMs: Long = 0L,
    entryStateStore: EntryStateStore,
    entryStateKey: EntryStateKey,
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
        entryStateStore = entryStateStore,
        entryStateKey = entryStateKey,
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

    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            delay(500)
        }
    }

    // Auto-hide after 3s on Pad.
    LaunchedEffect(controllerVisible) {
        if (controllerVisible) {
            delay(PAD_CONTROLLER_AUTO_HIDE_MS)
            controllerVisible = false
        }
    }

    BackHandler { scope.launch { engine.release(); onExit() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { controllerVisible = !controllerVisible }
            },
    ) {
        PadPlayerView(
            player = exo,
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
    }
}
