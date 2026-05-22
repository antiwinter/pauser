package com.opentune.player.controller

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.opentune.player.R
import com.opentune.player.engine.PlayerStores
import com.opentune.player.engine.toMediaSource
import com.opentune.content.contract.PlaybackSpec
import com.opentune.content.contract.SubtitleTrack
import com.opentune.storage.EntryStateKey
import com.opentune.storage.SubtitlePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUB_LOG_TAG = "OT_Subtitle"

// ---------------------------------------------------------------------------
// Subtitle resolution helpers — used during initial playback setup
// ---------------------------------------------------------------------------

internal data class SubtitlePreference(
    val externalUri: Uri? = null,
    val language: String? = null,
)

@UnstableApi
internal fun resolveSubtitlePreference(
    savedId: String?,
    spec: PlaybackSpec,
): SubtitlePreference {
    if (savedId == null) return SubtitlePreference()
    if (spec.subtitleTracks.isNotEmpty()) {
        val track = spec.subtitleTracks.find { it.trackId == savedId }
        if (track != null) {
            return if (track.externalRef != null) {
                SubtitlePreference(externalUri = Uri.parse(track.externalRef!!), language = track.language)
            } else {
                SubtitlePreference(language = track.language)
            }
        }
    }
    // ExoPlayer-native ID like "exo_<groupId>" — language unknown, let ExoPlayer auto-select.
    return SubtitlePreference()
}

@UnstableApi
internal fun prepareWithSidecar(
    context: Context,
    exo: ExoPlayer,
    subtitleUri: Uri,
    mimeType: String,
    spec: PlaybackSpec,
) {
    val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration
        .Builder(subtitleUri)
        .setMimeType(mimeType)
        .build()
    val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
        spec.httpClient.newBuilder()
            .apply {
                if (spec.headers.isNotEmpty()) addInterceptor { chain ->
                    val req = chain.request().newBuilder().apply {
                        spec.headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    chain.proceed(req)
                }
            }
            .build()
    )
    val subtitleSource = SingleSampleMediaSource
        .Factory(DefaultDataSource.Factory(context, httpFactory))
        .createMediaSource(subtitleConfig, C.TIME_UNSET)
    val mergedSource = MergingMediaSource(spec.toMediaSource(context), subtitleSource)
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setSelectUndeterminedTextLanguage(true)
        .build()
    exo.setMediaSource(mergedSource)
    exo.playWhenReady = true
    exo.prepare()
}

@UnstableApi
internal class SubtitleController(
    private val currentTracksState: MutableState<Tracks>,
    private val activeTrackIdState: MutableState<String?>,
    private val offsetFractionState: MutableState<Float>,
    private val sizeScaleState: MutableState<Float>,
    private val isAdjustActiveState: MutableState<Boolean>,
    private val screenHeightPxState: MutableState<Float>,
    private val scope: CoroutineScope,
    private val stores: PlayerStores,
    private val entryStateKey: EntryStateKey,
    private val parentStateKey: EntryStateKey?,
    private val seriesStateKey: EntryStateKey?,
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
        scope.launch(Dispatchers.IO) {
            stores.appConfigStore.saveSubtitlePrefs(SubtitlePrefs(offset, scale))
            Log.d(SUB_LOG_TAG, "confirmAdjust: saved subtitle prefs")
        }
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

    private fun buildSubtitleChildren(): List<PlayerMenuEntry> {
        val spec = specState.value
        val tracks = currentTracksState.value
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
                    Log.d(SUB_LOG_TAG, "SAVE subtitle track: Off for key=$entryStateKey")
                    stores.entryStateStore.upsertSubtitleTrack(entryStateKey, null)
                    parentStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, null) }
                    seriesStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, null) }
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
                                Log.d(SUB_LOG_TAG, "SAVE subtitle track: ExoNative gid=$gid for key=$entryStateKey")
                                stores.entryStateStore.upsertSubtitleTrack(entryStateKey, gid)
                                parentStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, gid) }
                                seriesStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, gid) }
                            }
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
                Log.d(SUB_LOG_TAG, "SAVE subtitle track: FromSpec embedded trackId=${track.trackId} for key=$entryStateKey")
                stores.entryStateStore.upsertSubtitleTrack(entryStateKey, track.trackId)
                parentStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, track.trackId) }
                seriesStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, track.trackId) }
            }
        } else {
            // External sidecar: stop the player, re-prepare with a MergingMediaSource, then
            // resume from the same position. prepareWithSidecar sets
            // setSelectUndeterminedTextLanguage(true) so ExoPlayer auto-selects the new track.
            Log.d(SUB_LOG_TAG, "select: FromSpec external trackId=${track.trackId} externalRef=${track.externalRef}")
            scope.launch {
                val pos = exo.currentPosition
                exo.stop()
                prepareWithSidecar(
                    context = context,
                    exo = exo,
                    subtitleUri = Uri.parse(track.externalRef!!),
                    mimeType = subtitleMimeType(track.externalRef!!),
                    spec = specState.value,
                )
                exo.seekTo(pos)
                activeTrackIdState.value = track.trackId
                withContext(Dispatchers.IO) {
                    Log.d(SUB_LOG_TAG, "SAVE subtitle track: FromSpec external trackId=${track.trackId} for key=$entryStateKey")
                    stores.entryStateStore.upsertSubtitleTrack(entryStateKey, track.trackId)
                    parentStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, track.trackId) }
                    seriesStateKey?.let { stores.entryStateStore.upsertSubtitleTrack(it, track.trackId) }
                }
            }
        }
    }
}

@UnstableApi
@Composable
internal fun rememberSubtitleController(
    exo: ExoPlayer,
    spec: PlaybackSpec,
    stores: PlayerStores,
    entryStateKey: EntryStateKey,
    parentStateKey: EntryStateKey? = null,
    seriesStateKey: EntryStateKey? = null,
    initialTrackId: String?,
    initialOffsetFraction: Float,
    initialSizeScale: Float,
): SubtitleController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specState = rememberUpdatedState(spec)

    Log.d(SUB_LOG_TAG, "rememberSubtitleController: initialTrackId=$initialTrackId offset=$initialOffsetFraction scale=$initialSizeScale")

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
                Log.d(SUB_LOG_TAG, "onTracksChanged: textGroups=${textGroups.size}" +
                    textGroups.joinToString(prefix = " [", postfix = "]") { g ->
                        val fmt = if (g.length > 0) g.getTrackFormat(0) else null
                        "id=${g.mediaTrackGroup.id} lang=${fmt?.language} mime=${fmt?.sampleMimeType} supported=${g.isSupported}"
                    })
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
            entryStateKey = entryStateKey,
            parentStateKey = parentStateKey,
            seriesStateKey = seriesStateKey,
            specState = specState,
            context = context,
            exo = exo,
        )
    }
}
