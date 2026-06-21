package com.opentune.player.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession
import com.opentune.player.engine.subtitleMimeType
import com.opentune.player.engine.toMediaSource
import com.opentune.player.PlaybackSpec
import com.opentune.player.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUB_LOG_TAG = "OT_Subtitle"

@UnstableApi
internal fun prepareWithSidecar(
    context: Context,
    exo: ExoPlayer,
    subtitleUri: Uri,
    mimeType: String,
    spec: PlaybackSpec,
) {
    Log.d(SUB_LOG_TAG, "prepareWithSidecar: rebuilding video source for sidecar sub uri=$subtitleUri")
    val subtitleConfig = MediaItem.SubtitleConfiguration
        .Builder(subtitleUri)
        .setMimeType(mimeType)
        .build()
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setSelectUndeterminedTextLanguage(true)
        .build()
    exo.setMediaSource(spec.toMediaSource(context, subtitleConfig))
    exo.playWhenReady = true
    exo.prepare()
}

@UnstableApi
internal class SubtitleManager(
    private val currentTracksState: MutableState<Tracks>,
    private val activeTrackId: State<String?>,
    private val offsetFractionState: MutableState<Float>,
    private val sizeScaleState: MutableState<Float>,
    private val isAdjustActiveState: MutableState<Boolean>,
    private val screenHeightPxState: MutableState<Float>,
    private val scope: CoroutineScope,
    private val session: PlaybackSession,
    private val specState: State<PlaybackSpec>,
    private val context: Context,
    private val exo: ExoPlayer,
) {
    val translationYPx: Float get() = offsetFractionState.value * screenHeightPxState.value
    val sizeScale: Float get() = sizeScaleState.value
    val isAdjustActive: Boolean get() = isAdjustActiveState.value

    private val offsetStep: Float get() =
        if (screenHeightPxState.value > 0f) 20f / screenHeightPxState.value else 0f

    fun adjustOffsetUp() { offsetFractionState.value -= offsetStep }
    fun adjustOffsetDown() { offsetFractionState.value += offsetStep }
    fun adjustScaleDown() { sizeScaleState.value = (sizeScaleState.value - 0.1f).coerceAtLeast(0.3f) }
    fun adjustScaleUp() { sizeScaleState.value = (sizeScaleState.value + 0.1f).coerceAtMost(3f) }

    internal fun confirmAdjust() {
        isAdjustActiveState.value = false
        val offset = offsetFractionState.value
        val scale = sizeScaleState.value
        Log.d(SUB_LOG_TAG, "confirmAdjust: offset=$offset scale=$scale")
        session.updateSubtitlePrefs(offset, scale)
    }

    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable { stringResource(R.string.player_settings_subtitles) },
        children = ::buildSubtitleChildren,
        isSelected = { false },
        onSelect = {},
    )

    val adjustMenuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable { stringResource(R.string.subtitle_adjust_mode_label) },
        children = { emptyList() },
        isSelected = { isAdjustActiveState.value },
        onSelect = { isAdjustActiveState.value = true },
    )

    private fun saveSubtitleTrack(trackId: String?) {
        session.updateSubtitleTrackId(trackId)
    }

    private fun buildSubtitleChildren(): List<PlayerMenuEntry> {
        val spec = specState.value
        val source = spec.sources[spec.state.sourceIndex]
        val tracks = currentTracksState.value
        val entries = mutableListOf<PlayerMenuEntry>()

        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.subtitle_track_none) },
            children = { emptyList() },
            isSelected = { activeTrackId.value == null },
            onSelect = {
                Log.d(SUB_LOG_TAG, "select: Off — disabling text track type")
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
                saveSubtitleTrack(null)
            },
        )

        if (source.subtitleTracks.isNotEmpty()) {
            source.subtitleTracks.forEach { track ->
                val exoGroup = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .firstOrNull { it.mediaTrackGroup.id == track.trackId }
                val exoLabel = if (exoGroup != null && exoGroup.length > 0) exoGroup.getTrackFormat(0).label else null
                val exoLang = if (exoGroup != null && exoGroup.length > 0) exoGroup.getTrackFormat(0).language else null
                val label = buildTrackLabel(track, exoLabel, exoLang)
                entries += PlayerMenuEntry(
                    label = @Composable { label },
                    children = { emptyList() },
                    isSelected = { activeTrackId.value == track.trackId },
                    onSelect = { selectFromSpec(track) },
                )
            }
        } else {
            tracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .forEachIndexed { idx, group ->
                    val label = buildExoTrackLabel(group, idx)
                    val gid = "exo_${group.mediaTrackGroup.id}"
                    entries += PlayerMenuEntry(
                        label = @Composable { label },
                        children = { emptyList() },
                        isSelected = { activeTrackId.value == gid },
                        onSelect = {
                            Log.d(SUB_LOG_TAG, "select: ExoNative gid=$gid")
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                .build()
                            saveSubtitleTrack(gid)
                        },
                    )
                }
        }

        return entries
    }

    private fun selectFromSpec(track: SubtitleTrack) {
        if (track.externalRef == null) {
            val exoGroup = currentTracksState.value.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .firstOrNull { it.mediaTrackGroup.id == track.trackId }
            Log.d(SUB_LOG_TAG, "select: FromSpec embedded trackId=${track.trackId}")
            val params = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            if (exoGroup != null) {
                params.setOverrideForType(TrackSelectionOverride(exoGroup.mediaTrackGroup, 0))
            } else if (track.language != null) {
                params.setPreferredTextLanguage(track.language)
            }
            exo.trackSelectionParameters = params.build()
            saveSubtitleTrack(track.trackId)
        } else {
            Log.d(SUB_LOG_TAG, "select: FromSpec external trackId=${track.trackId}")
            scope.launch {
                val pos = exo.currentPosition
                val spec = specState.value

                // Recover by re-preparing with just the video source if sidecar fails.
                val recoveryListener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        exo.removeListener(this)
                        Log.w(SUB_LOG_TAG, "Subtitle sidecar failed (code=${error.errorCode}), replaying without it")
                        exo.setMediaSource(spec.toMediaSource(context))
                        exo.playWhenReady = true
                        exo.prepare()
                        exo.seekTo(pos)
                        saveSubtitleTrack(null)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) exo.removeListener(this)
                    }
                }
                exo.addListener(recoveryListener)

                prepareWithSidecar(
                    context = context,
                    exo = exo,
                    subtitleUri = Uri.parse(track.externalRef!!),
                    mimeType = subtitleMimeType(track.externalRef!!),
                    spec = spec,
                )
                exo.seekTo(pos)
                saveSubtitleTrack(track.trackId)
            }
        }
    }
}

@UnstableApi
@Composable
internal fun rememberSubtitleManager(
    exo: ExoPlayer,
    spec: PlaybackSpec,
    session: PlaybackSession,
): SubtitleManager {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specState = rememberUpdatedState(spec)
    val instanceKey = spec.sources[spec.state.sourceIndex].url

    val activeTrackId = session.subtitleTrackIdFlow.collectAsState()
    val offsetFractionState = remember(instanceKey) {
        mutableStateOf(session.subtitleOffsetFractionFlow.value)
    }
    val sizeScaleState = remember(instanceKey) {
        mutableStateOf(session.subtitleSizeScaleFlow.value)
    }
    val isAdjustActiveState = remember { mutableStateOf(false) }
    val screenHeightPxState = remember { mutableStateOf(0f) }
    val currentTracksState = remember(instanceKey) { mutableStateOf(Tracks.EMPTY) }

    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    screenHeightPxState.value = screenHeightPx

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                currentTracksState.value = tracks
            }
        }
        exo.addListener(listener)
        currentTracksState.value = exo.currentTracks
        onDispose { exo.removeListener(listener) }
    }

    return remember(exo, session, instanceKey) {
        SubtitleManager(
            currentTracksState = currentTracksState,
            activeTrackId = activeTrackId,
            offsetFractionState = offsetFractionState,
            sizeScaleState = sizeScaleState,
            isAdjustActiveState = isAdjustActiveState,
            screenHeightPxState = screenHeightPxState,
            scope = scope,
            session = session,
            specState = specState,
            context = context,
            exo = exo,
        )
    }
}
