package com.opentune.player.engine

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.PlaybackSpec
import com.opentune.player.manager.AudioManager
import com.opentune.player.manager.HdrManager
import com.opentune.player.manager.SpeedManager
import com.opentune.player.manager.SubtitleManager
import com.opentune.player.manager.rememberAudioManager
import com.opentune.player.manager.rememberHdrManager
import com.opentune.player.manager.rememberSpeedManager
import com.opentune.player.manager.rememberSubtitleManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class PlaybackSurface(
    val exo: ExoPlayer,
    val subtitleCtrl: SubtitleManager,
    val audioCtrl: AudioManager,
    val speedCtrl: SpeedManager,
    val hdrCtrl: HdrManager,
    val trackInfo: State<TrackInfo>,
    val bandwidthMbps: MutableFloatState,
    private val session: PlaybackSession,
) {
    fun leaveSurface() {
        session.pause()
    }
}

@UnstableApi
@Composable
internal fun rememberPlaybackSurface(
    spec: PlaybackSpec,
    session: PlaybackSession,
): PlaybackSurface {
    val context = LocalContext.current
    val specState = rememberUpdatedState(spec)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val exo = session.exo
    val instanceKey = spec.sources[spec.state.sourceIndex].url

    val trackInfo = rememberTrackInfo(exo, instanceKey, mainHandler)
    val bandwidthMbps = remember(instanceKey) { mutableFloatStateOf(-1f) }
    val hdrCtrl = rememberHdrManager(trackInfo)
    val subtitleCtrl = rememberSubtitleManager(
        exo = exo,
        spec = spec,
        session = session,
    )
    val audioCtrl = rememberAudioManager(
        exo = exo,
        session = session,
    )
    val speedCtrl = rememberSpeedManager(
        exo = exo,
        session = session,
    )

    val engine = remember(instanceKey) {
        PlaybackSurface(
            exo = exo,
            subtitleCtrl = subtitleCtrl,
            audioCtrl = audioCtrl,
            speedCtrl = speedCtrl,
            hdrCtrl = hdrCtrl,
            trackInfo = trackInfo,
            bandwidthMbps = bandwidthMbps,
            session = session,
        )
    }

    LaunchedEffect(instanceKey) {
        while (isActive) {
            delay(1_000)
            bandwidthMbps.floatValue = BandwidthTracker.mbps
        }
    }

    TrackFallbackEffect(
        exo = exo,
        instanceKey = instanceKey,
        specState = specState,
        trackInfoState = trackInfo,
        mainHandler = mainHandler,
        context = context,
    )

    return engine
}
