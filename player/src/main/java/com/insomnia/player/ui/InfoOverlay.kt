package com.insomnia.player.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.insomnia.core.theme.ScrimStrong
import com.insomnia.core.theme.StatusOk
import com.insomnia.core.theme.StatusFail
import com.insomnia.player.PlaybackDisplayInfo
import com.insomnia.player.PlaybackSpec
import com.insomnia.player.formatBitrate
import com.insomnia.player.engine.PlaybackSession
import com.insomnia.player.manager.TrackInfo
import timber.log.Timber

@UnstableApi
internal class InfoOverlayState(
    val displayInfo: PlaybackDisplayInfo,
    val trackInfo: TrackInfo,
    val tracks: Tracks,
    val displaySupportsHdr: Boolean,
    val bitrate: Int?,
    val sourceIndex: Int,
    val sourceCount: Int,
    private val showState: MutableState<Boolean>,
    val mbpsState: MutableFloatState,
) {
    val isVisible: Boolean get() = showState.value

    fun show() { showState.value = true }
    fun hide() { showState.value = false }
}

@UnstableApi
@Composable
internal fun InfoOverlay(state: InfoOverlayState) {
    if (!state.isVisible) return
    val ti = state.trackInfo
    val videoMime = ti.videoMime ?: state.tracks.allMimes(C.TRACK_TYPE_VIDEO)
    val audioMime = ti.audioMime ?: state.tracks.allMimes(C.TRACK_TYPE_AUDIO)
    val isHdrEnabled = ti.isHdrCapable && state.displaySupportsHdr
    val mbps = state.mbpsState.floatValue
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ScrimStrong)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = state.displayInfo.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                if (state.sourceCount > 1) {
                    Text(
                        text = "Source ${state.sourceIndex + 1}/${state.sourceCount}",
                        color = StatusOk,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    text = trackLabel(videoMime, ti.videoDecoderStatus),
                    color = if (isTrackFailed(videoMime, ti.videoDecoderStatus)) StatusFail else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                Text(
                    text = trackLabel(audioMime, ti.audioDecoderStatus),
                    color = if (isTrackFailed(audioMime, ti.audioDecoderStatus)) StatusFail else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                if (isHdrEnabled) {
                    Text(
                        text = "HDR",
                        color = StatusOk,
                        fontSize = 14.sp,
                    )
                }
                state.bitrate?.takeIf { it > 0 }?.let { br ->
                    Text(text = formatBitrate(br.toFloat()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            if (mbps >= 0f) {
                Text(
                    text = formatBitrate(mbps * 1_000_000f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@UnstableApi
private fun Tracks.allMimes(type: Int): String? {
    val mimes = groups.filter { it.type == type }
        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).sampleMimeType } }
        .filterNotNull()
        .distinct()
    return mimes.takeIf { it.isNotEmpty() }?.joinToString(",")
}

private fun trackLabel(mime: String?, decoderStatus: String?): String {
    if (mime.isNullOrEmpty()) return ""
    val codecs = mime.split(',')
        .map { it.replace(Regex("^(?:video|audio)/"), "") }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")
    if (codecs.isEmpty()) return ""
    return if (decoderStatus.isNullOrEmpty() || decoderStatus == "passthrough") codecs else "$codecs[$decoderStatus]"
}

private fun isTrackFailed(mime: String?, decoderStatus: String?): Boolean {
    return mime != null && decoderStatus == "err"
}

private fun displaySupportsHdr(context: Context): Boolean {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    return dm.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
}

@UnstableApi
@Composable
internal fun rememberInfoOverlayState(
    instanceKey: String,
    displayInfo: PlaybackDisplayInfo,
    session: PlaybackSession,
    spec: PlaybackSpec,
    bandwidthMbps: MutableFloatState,
): InfoOverlayState {
    val trackInfo by session.trackInfoFlow.collectAsState()
    val tracks by session.tracksFlow.collectAsState()
    val context = LocalContext.current
    val displaySupportsHdr = remember { displaySupportsHdr(context) }
    val bitrate = trackInfo.videoBitrate
        ?: spec.sources.getOrNull(spec.state.sourceIndex)?.mediaCodecs?.firstOrNull()?.bitrate
    val showState = remember(instanceKey) { mutableStateOf(false) }
    return remember(instanceKey, displayInfo, trackInfo, tracks, displaySupportsHdr, bitrate) {
        InfoOverlayState(
            displayInfo = displayInfo,
            trackInfo = trackInfo,
            tracks = tracks,
            displaySupportsHdr = displaySupportsHdr,
            bitrate = bitrate,
            sourceIndex = spec.state.sourceIndex,
            sourceCount = spec.sources.size,
            showState = showState,
            mbpsState = bandwidthMbps,
        )
    }
}
