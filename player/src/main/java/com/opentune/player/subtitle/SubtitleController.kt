package com.opentune.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.opentune.player.PlayerStores
import com.opentune.player.R
import com.opentune.player.menu.PlayerMenuEntry
import com.opentune.storage.MediaStateKey
import com.opentune.storage.SubtitlePrefs
import com.opentune.storage.upsertSubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_CoverExtractor" // reuse tag as the plan specifies OT_Subtitle
private const val SUB_LOG_TAG = "OT_Subtitle"

@UnstableApi
class SubtitleController(
    private val currentTracksState: MutableState<Tracks>,
    private val activeTrackIdState: MutableState<String?>,
    private val offsetFractionState: MutableState<Float>,
    private val sizeScaleState: MutableState<Float>,
    private val isAdjustActiveState: MutableState<Boolean>,
    private val screenHeightPxState: MutableState<Float>,
    private val scope: CoroutineScope,
    private val stores: PlayerStores,
    private val mediaStateKey: MediaStateKey,
    private val specState: State<com.opentune.provider.PlaybackSpec>,
    private val context: Context,
    private val exo: ExoPlayer,
) {
    val translationYPx: Float get() = offsetFractionState.value * screenHeightPxState.value
    val sizeScale: Float get() = sizeScaleState.value
    val isAdjustActive: Boolean get() = isAdjustActiveState.value

    private val offsetStep: Float get() =
        if (screenHeightPxState.value > 0f) 20f / screenHeightPxState.value else 0f

    val adjustDpadKey: (Int) -> Unit = { keyCode ->
        val isConfirm = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        Log.d(SUB_LOG_TAG, "adjustCallback keyCode=$keyCode before: offset=${offsetFractionState.value} scale=${sizeScaleState.value}")
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                offsetFractionState.value -= offsetStep
            android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                offsetFractionState.value += offsetStep
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                sizeScaleState.value = (sizeScaleState.value - 0.1f).coerceAtLeast(0.3f)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                sizeScaleState.value = (sizeScaleState.value + 0.1f).coerceAtMost(3f)
        }
        if (isConfirm) confirmAdjust()
    }

    /** Returns true if back was consumed (adjust mode was active). */
    fun handleBack(): Boolean {
        if (!isAdjustActiveState.value) return false
        confirmAdjust()
        return true
    }

    private fun confirmAdjust() {
        isAdjustActiveState.value = false
        val offset = offsetFractionState.value
        val scale = sizeScaleState.value
        scope.launch(Dispatchers.IO) {
            stores.appConfigStore?.saveSubtitlePrefs(SubtitlePrefs(offset, scale))
        }
    }

    @Composable
    fun AdjustOsd() {
        if (!isAdjustActiveState.value) return
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

    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable { stringResource(R.string.player_settings_subtitles) },
        children = ::buildSubtitleChildren,
        isSelected = { false },
        onSelect = {},
    )

    private fun buildSubtitleChildren(): List<PlayerMenuEntry> {
        val spec = specState.value
        val tracks = currentTracksState.value
        val activeId = activeTrackIdState.value
        val entries = mutableListOf<PlayerMenuEntry>()

        // Off
        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.subtitle_track_none) },
            children = { emptyList() },
            isSelected = { activeTrackIdState.value == null },
            onSelect = {
                Log.d(SUB_LOG_TAG, "select: Off — disabling text track type")
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
                activeTrackIdState.value = null
                scope.launch(Dispatchers.IO) {
                    stores.mediaStateStore.upsertSubtitleTrack(mediaStateKey, null)
                }
            },
        )

        if (spec.subtitleTracks.isNotEmpty()) {
            spec.subtitleTracks.forEach { track ->
                val exoGroup = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .firstOrNull { it.mediaTrackGroup.id == track.trackId }
                val exoLabel = if (exoGroup != null && exoGroup.length > 0) exoGroup.getTrackFormat(0).label else null
                val exoLang = if (exoGroup != null && exoGroup.length > 0) exoGroup.getTrackFormat(0).language else null
                val label = buildTrackLabel(track, exoLabel, exoLang)
                entries += PlayerMenuEntry(
                    label = @Composable { label },
                    children = { emptyList() },
                    isSelected = { activeTrackIdState.value == track.trackId },
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
                        isSelected = { activeTrackIdState.value == gid },
                        onSelect = {
                            Log.d(SUB_LOG_TAG, "select: ExoNative gid=$gid")
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                .build()
                            activeTrackIdState.value = gid
                            scope.launch(Dispatchers.IO) {
                                stores.mediaStateStore.upsertSubtitleTrack(mediaStateKey, gid)
                            }
                        },
                    )
                }
        }

        // Adjust option only when a track is active
        if (activeId != null) {
            entries += PlayerMenuEntry(
                label = @Composable { stringResource(R.string.subtitle_adjust_mode_label) },
                children = { emptyList() },
                isSelected = { false },
                onSelect = { isAdjustActiveState.value = true },
            )
        }

        return entries
    }

    private fun selectFromSpec(track: com.opentune.provider.SubtitleTrack) {
        if (track.externalRef == null) {
            val currentTracks = currentTracksState.value
            val exoGroup = currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .firstOrNull { it.mediaTrackGroup.id == track.trackId }
            Log.d(SUB_LOG_TAG, "select: FromSpec embedded trackId=${track.trackId} lang=${track.language} exoGroupMatch=${exoGroup?.let { "id=${it.mediaTrackGroup.id} isSupported=${it.isSupported}" } ?: "null"}")
            val params = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            if (exoGroup != null) {
                params.setOverrideForType(TrackSelectionOverride(exoGroup.mediaTrackGroup, 0))
            } else if (track.language != null) {
                params.setPreferredTextLanguage(track.language)
            }
            exo.trackSelectionParameters = params.build()
            activeTrackIdState.value = track.trackId
            scope.launch(Dispatchers.IO) {
                stores.mediaStateStore.upsertSubtitleTrack(mediaStateKey, track.trackId)
            }
        } else {
            val externalRef = track.externalRef!!
            Log.d(SUB_LOG_TAG, "select: FromSpec external trackId=${track.trackId} externalRef=$externalRef")
            scope.launch {
                val subtitleUri: Uri? = if (externalRef.startsWith("http://") || externalRef.startsWith("https://")) {
                    Uri.parse(externalRef)
                } else {
                    specState.value.resolveExternalSubtitle?.invoke(externalRef)
                }
                Log.d(SUB_LOG_TAG, "select: external resolved subtitleUri=$subtitleUri")
                if (subtitleUri == null) {
                    Log.w(SUB_LOG_TAG, "resolveExternalSubtitle returned null for $externalRef")
                    return@launch
                }
                val pos = withContext(Dispatchers.Main) { exo.currentPosition }
                val mimeType = subtitleMimeType(externalRef)
                val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration
                    .Builder(subtitleUri)
                    .setMimeType(mimeType)
                    .build()
                val subtitleSource = SingleSampleMediaSource
                    .Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(subtitleConfig, C.TIME_UNSET)
                val mergedSource = MergingMediaSource(specState.value.mediaSourceFactory.create(), subtitleSource)
                withContext(Dispatchers.Main) {
                    var sidecarListener: Player.Listener? = null
                    sidecarListener = object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            val supported = tracks.groups.filter {
                                it.type == C.TRACK_TYPE_TEXT && it.isSupported
                            }
                            Log.d(SUB_LOG_TAG, "sidecar.onTracksChanged: textSupported=${supported.size}")
                            if (supported.isNotEmpty()) {
                                exo.removeListener(this)
                                val sidecarGroup = supported.last()
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setOverrideForType(TrackSelectionOverride(sidecarGroup.mediaTrackGroup, 0))
                                    .build()
                            }
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(SUB_LOG_TAG, "sidecar.onPlayerError: ${error.message}")
                            exo.removeListener(this)
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_IDLE) exo.removeListener(this)
                        }
                    }
                    exo.addListener(sidecarListener)
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                    exo.stop()
                    exo.setMediaSource(mergedSource)
                    exo.playWhenReady = true
                    exo.prepare()
                    exo.seekTo(pos)
                }
                activeTrackIdState.value = track.trackId
                withContext(Dispatchers.IO) {
                    stores.mediaStateStore.upsertSubtitleTrack(mediaStateKey, track.trackId)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun rememberSubtitleController(
    exo: ExoPlayer,
    spec: com.opentune.provider.PlaybackSpec,
    stores: PlayerStores,
    mediaStateKey: MediaStateKey,
    initialTrackId: String?,
    initialOffsetFraction: Float,
    initialSizeScale: Float,
): SubtitleController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specState = rememberUpdatedState(spec)

    val currentTracksState = remember { mutableStateOf(Tracks.EMPTY) }
    val activeTrackIdState = remember { mutableStateOf(initialTrackId) }
    val offsetFractionState = remember { mutableStateOf(initialOffsetFraction) }
    val sizeScaleState = remember { mutableStateOf(initialSizeScale) }
    val isAdjustActiveState = remember { mutableStateOf(false) }
    val screenHeightPxState = remember { mutableStateOf(0f) }

    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    screenHeightPxState.value = screenHeightPx

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(SUB_LOG_TAG, "onTracksChanged: textGroups=${textGroups.size}")
                currentTracksState.value = tracks
            }
        }
        exo.addListener(listener)
        currentTracksState.value = exo.currentTracks
        onDispose { exo.removeListener(listener) }
    }

    return remember {
        SubtitleController(
            currentTracksState = currentTracksState,
            activeTrackIdState = activeTrackIdState,
            offsetFractionState = offsetFractionState,
            sizeScaleState = sizeScaleState,
            isAdjustActiveState = isAdjustActiveState,
            screenHeightPxState = screenHeightPxState,
            scope = scope,
            stores = stores,
            mediaStateKey = mediaStateKey,
            specState = specState,
            context = context,
            exo = exo,
        )
    }
}
