package com.opentune.player.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.opentune.player.PlaybackDisplayInfo
import com.opentune.player.PlaybackSpec
import com.opentune.player.formatBitrate
import com.opentune.player.engine.PlaybackSession
import com.opentune.player.manager.TrackInfo
import timber.log.Timber

@UnstableApi
internal class InfoOverlayState(
    val displayInfo: PlaybackDisplayInfo,
    val trackInfo: TrackInfo,
    val tracks: Tracks,
    val displaySupportsHdr: Boolean,
    val bitrate: Int?,
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
    // When no track of a type is selected (e.g. renderer disabled by a decode error), fall back to
    // every advertised mime of that type so the user still sees which formats the container carries.
    val videoMime = ti.videoMime ?: state.tracks.allMimes(C.TRACK_TYPE_VIDEO)
    val audioMime = ti.audioMime ?: state.tracks.allMimes(C.TRACK_TYPE_AUDIO)
    val isHdrEnabled = ti.isHdrCapable && state.displaySupportsHdr
    val mbps = state.mbpsState.floatValue
    Timber.d(
        "render title='${state.displayInfo.title}' vMime=$videoMime vDec=${ti.videoDecoderStatus} " +
            "aMime=$audioMime aDec=${ti.audioDecoderStatus} bitrate=${state.bitrate} mbps=$mbps"
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = state.displayInfo.title, color = Color.White, fontSize = 14.sp)
                Text(
                    text = trackLabel(videoMime, ti.videoDecoderStatus),
                    color = if (isTrackFailed(videoMime, ti.videoDecoderStatus)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
                Text(
                    text = trackLabel(audioMime, ti.audioDecoderStatus),
                    color = if (isTrackFailed(audioMime, ti.audioDecoderStatus)) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 14.sp,
                )
                if (isHdrEnabled) {
                    Text(
                        text = "HDR",
                        color = Color(0xFF4CAF50),
                        fontSize = 14.sp,
                    )
                }
                state.bitrate?.takeIf { it > 0 }?.let { br ->
                    Text(text = formatBitrate(br.toFloat()), color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }
            // Download speed on the right; hidden only before first measurement (-1 sentinel).
            if (mbps >= 0f) {
                Text(
                    text = formatBitrate(mbps * 1_000_000f),
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** All distinct mimes advertised for [type] across every group, or null when none exist. */
@UnstableApi
private fun Tracks.allMimes(type: Int): String? {
    val mimes = groups.filter { it.type == type }
        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).sampleMimeType } }
        .filterNotNull()
        .distinct()
    return mimes.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/** "codec[status]", or bare "codec" when status is passthrough/empty (no decoder in the path).
 *  Accepts a comma-separated mime list (the all-mimes fallback); each is stripped of its
 *  video/audio prefix and deduped. */
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

/** Returns true if the track has a decode error. */
private fun isTrackFailed(mime: String?, decoderStatus: String?): Boolean {
    return mime != null && decoderStatus == "err"
}

/** One-time check: does the default display support any HDR type? */
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
            showState = showState,
            mbpsState = bandwidthMbps,
        )
    }
}
