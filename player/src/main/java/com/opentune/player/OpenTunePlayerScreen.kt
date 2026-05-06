package com.opentune.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.SubtitleTrack
import com.opentune.storage.MediaStateKey
import com.opentune.storage.UserMediaStateStore
import com.opentune.storage.upsertPosition
import com.opentune.storage.upsertSpeed
import com.opentune.storage.upsertSubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val LOG_TAG = "OpenTunePlayerShell"
private const val SUB_LOG_TAG = "OT_Subtitle"

private const val MAX_WAIT_READY_MS = 120_000L

private const val MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS = 2_500L

private val SPEED_VALUES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SPEED_LABELS = SPEED_VALUES.map { if (it == 1f) "1×" else "${it}×" }

private enum class PlayerMenuScreen { None, TopLevel, Subtitles, Audio, Speed }

private sealed class SubtitleOption {
    data object Off : SubtitleOption()
    data class FromSpec(val track: SubtitleTrack) : SubtitleOption()
    data class ExoNative(val group: Tracks.Group) : SubtitleOption()
    data object Adjust : SubtitleOption()
}

@Composable
private fun PlayerOverlayMenuItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier) {
    M3Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFF3D3D3D) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (isSelected) Color.White else Color(0xFFCCCCCC),
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlayerSettingsOverlay(
    menuScreen: PlayerMenuScreen,
    menuTopIndex: Int,
    subtitleOptions: List<SubtitleOption>,
    subtitleMenuIndex: Int,
    audioGroups: List<Tracks.Group>,
    audioMenuIndex: Int,
    speedMenuIndex: Int,
    currentTracks: Tracks = Tracks.EMPTY,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .background(Color(0xFF1C1C1C), shape = RoundedCornerShape(12.dp))
                .padding(vertical = 8.dp),
        ) {
            when (menuScreen) {
                PlayerMenuScreen.TopLevel -> {
                    listOf(
                        R.string.player_settings_subtitles,
                        R.string.player_settings_audio,
                        R.string.player_settings_speed,
                    ).forEachIndexed { i, resId ->
                        PlayerOverlayMenuItem(
                            label = androidx.compose.ui.res.stringResource(resId),
                            isSelected = i == menuTopIndex,
                        )
                    }
                }
                PlayerMenuScreen.Subtitles -> {
                    val items = subtitleOptions
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(items) { i, option ->
                            val label = when (option) {
                                SubtitleOption.Off -> androidx.compose.ui.res.stringResource(R.string.subtitle_track_none)
                                SubtitleOption.Adjust -> androidx.compose.ui.res.stringResource(R.string.subtitle_adjust_mode_label)
                                is SubtitleOption.FromSpec -> {
                                    val exoGroup = currentTracks.groups
                                        .filter { it.type == C.TRACK_TYPE_TEXT }
                                        .firstOrNull { it.mediaTrackGroup.id == option.track.trackId }
                                    val exoLabel = if (exoGroup != null && exoGroup.length > 0)
                                        exoGroup.getTrackFormat(0).label else null
                                    val exoLang = if (exoGroup != null && exoGroup.length > 0)
                                        exoGroup.getTrackFormat(0).language else null
                                    buildTrackLabel(option.track, exoLabel, exoLang)
                                }
                                is SubtitleOption.ExoNative -> buildExoTrackLabel(option.group, i)
                            }
                            PlayerOverlayMenuItem(label = label, isSelected = i == subtitleMenuIndex)
                        }
                    }
                }
                PlayerMenuScreen.Audio -> {
                    PlayerOverlayMenuItem(
                        label = androidx.compose.ui.res.stringResource(R.string.player_audio_auto),
                        isSelected = audioMenuIndex == 0,
                    )
                    audioGroups.forEachIndexed { i, group ->
                        PlayerOverlayMenuItem(
                            label = buildAudioGroupLabel(group, i),
                            isSelected = audioMenuIndex == i + 1,
                        )
                    }
                }
                PlayerMenuScreen.Speed -> {
                    SPEED_LABELS.forEachIndexed { i, label ->
                        PlayerOverlayMenuItem(label = label, isSelected = i == speedMenuIndex)
                    }
                }
                PlayerMenuScreen.None -> {}
            }
        }
    }
}

@Composable
private fun SubtitleAdjustOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        M3Text(
            text = androidx.compose.ui.res.stringResource(R.string.subtitle_adjust_hint),
            modifier = Modifier
                .padding(bottom = 72.dp)
                .background(Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}

private fun buildTrackLabel(track: SubtitleTrack, exoLabel: String? = null, exoLang: String? = null): String {
    val base = exoLabel?.takeIf { it.isNotBlank() } ?: track.label
    val langTag = languageDisplayName(exoLang ?: track.language)
    val flags = buildString {
        if (track.isDefault) append(" ●")
        if (track.isForced) append(" (Forced)")
    }
    return "[${langTag}] ${track.trackId} $base$flags"
}

private fun languageDisplayName(lang: String?): String = when (lang?.lowercase()?.take(3)) {
    "zh", "chi", "zho" -> "Chinese"
    "en", "eng" -> "English"
    "ja", "jpn" -> "Japanese"
    "ko", "kor" -> "Korean"
    "fr", "fre", "fra" -> "French"
    "de", "ger", "deu" -> "German"
    "es", "spa" -> "Spanish"
    "it", "ita" -> "Italian"
    "pt", "por" -> "Portuguese"
    "ru", "rus" -> "Russian"
    "ar", "ara" -> "Arabic"
    "th", "tha" -> "Thai"
    "vi", "vie" -> "Vietnamese"
    null, "und", "" -> "Unknown"
    else -> lang ?: "Unknown"
}

@UnstableApi
private fun buildExoTrackLabel(group: Tracks.Group, fallbackIndex: Int): String {
    if (group.length == 0) return "Track ${fallbackIndex + 1}"
    val fmt = group.getTrackFormat(0)
    val lang = fmt.language
    val lbl = fmt.label
    return when {
        !lbl.isNullOrBlank() -> lbl
        !lang.isNullOrBlank() -> lang
        else -> "Track ${fallbackIndex + 1}"
    }
}

@UnstableApi
private fun buildAudioGroupLabel(group: Tracks.Group, index: Int): String {
    if (group.length == 0) return "Audio ${index + 1}"
    val fmt = group.getTrackFormat(0)
    val lang = fmt.language
    val lbl = fmt.label
    val channels = if (fmt.channelCount > 0) " (${fmt.channelCount}ch)" else ""
    return when {
        !lbl.isNullOrBlank() -> lbl + channels
        !lang.isNullOrBlank() -> lang + channels
        else -> "Audio ${index + 1}$channels"
    }
}

@UnstableApi
private fun subtitleMimeType(ref: String): String {
    // Strip query string before looking at the extension
    val path = ref.substringBefore('?')
    return when (path.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun OpenTunePlayerScreen(
    spec: PlaybackSpec,
    mediaStateStore: UserMediaStateStore,
    mediaStateKey: MediaStateKey,
    onExit: () -> Unit,
    initialSubtitleTrackId: String? = null,
    initialSubtitleOffsetFraction: Float = 0f,
    initialSubtitleSizeScale: Float = 1f,
    appConfigStore: com.opentune.storage.DataStoreAppConfigStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specState = rememberUpdatedState(spec)
    val onExitState = rememberUpdatedState(onExit)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val instanceKey = mediaStateKey
    val exo = remember(instanceKey) { OpenTuneExoPlayer.createForBundledSources(context) }
    val released = remember(instanceKey) { AtomicBoolean(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var showAudioUnsupportedBanner by remember { mutableStateOf(false) }

    // --- Subtitle / settings state ---
    var menuScreen by remember { mutableStateOf(PlayerMenuScreen.None) }
    var menuTopIndex by remember { mutableStateOf(0) }
    var subtitleMenuIndex by remember { mutableStateOf(0) }
    var audioMenuIndex by remember { mutableStateOf(0) }
    var speedMenuIndex by remember { mutableStateOf(SPEED_VALUES.indexOf(1f)) }
    var activeSubtitleTrackId by remember { mutableStateOf(initialSubtitleTrackId) }
    var subtitleOffsetFraction by remember { mutableStateOf(initialSubtitleOffsetFraction) }
    var subtitleSizeScale by remember { mutableStateOf(initialSubtitleSizeScale) }
    var isSubtitleAdjustActive by remember { mutableStateOf(false) }
    var currentTracks by remember { mutableStateOf(Tracks.EMPTY) }

    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(SUB_LOG_TAG, "onTracksChanged: textGroups=${textGroups.size}")
                textGroups.forEachIndexed { i, g ->
                    val fmt = if (g.length > 0) g.getTrackFormat(0) else null
                    Log.d(SUB_LOG_TAG, "  text[$i] id=${g.mediaTrackGroup.id} lang=${fmt?.language} label=${fmt?.label} isSelected=${g.isSelected} isSupported=${g.isSupported}")
                }
                currentTracks = tracks
            }
        }
        exo.addListener(listener)
        currentTracks = exo.currentTracks
        onDispose { exo.removeListener(listener) }
    }

    suspend fun shutdown(userInitiatedExit: Boolean) {
        val s = specState.value
        Log.d(LOG_TAG, "shutdown userInitiated=$userInitiatedExit alreadyReleased=${released.get()}")
        if (!released.compareAndSet(false, true)) {
            if (userInitiatedExit) {
                withContext(Dispatchers.Main) { onExitState.value() }
            }
            return
        }
        // Wrap cleanup in NonCancellable so it survives the composition cancellation
        // triggered by onExit() navigating away.
        withContext(NonCancellable) {
            val pos = withContext(Dispatchers.Main) { exo.currentPosition }
            withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            s.hooks.onStop(pos)
            withContext(Dispatchers.Main) { exo.release() }
            s.onPlaybackDispose()
        }
        if (userInitiatedExit) {
            withContext(Dispatchers.Main) { onExitState.value() }
        }
    }

    fun playbackRate(): Float = exo.playbackParameters.speed

    val hooksState = rememberUpdatedState(spec.hooks)

    LaunchedEffect(instanceKey) {
        val s = spec
        released.set(false)
        showAudioUnsupportedBanner = false
        val savedSpeed = withContext(Dispatchers.IO) {
            mediaStateStore.get(
                instanceKey.providerType,
                instanceKey.sourceId,
                instanceKey.itemRef
            )?.playbackSpeed ?: 1f
        }.coerceIn(0.25f, 4f)
        withContext(Dispatchers.Main) {
            exo.playbackParameters = PlaybackParameters(savedSpeed)
            exo.stop()
            exo.setMediaSource(s.mediaSourceFactory.create())
            exo.playWhenReady = true
            exo.prepare()
        }

        val hooks = s.hooks
        Log.d(
            LOG_TAG,
            "readyEffect start initialPositionMs=${s.initialPositionMs} progressIntervalMs=${hooks.progressIntervalMs()}",
        )
        val strictReadyWait = hooks.progressIntervalMs() > 0L || s.initialPositionMs > 0L
        val maxReadyMs = if (strictReadyWait) MAX_WAIT_READY_MS else MAX_WAIT_READY_NO_PROGRESS_HOOKS_MS
        if (!strictReadyWait) {
            Log.d(LOG_TAG, "readyEffect soft READY wait (no progress hooks / typical SMB)")
        }
        var waitedReadyMs = 0L
        while (exo.playbackState != Player.STATE_READY && isActive) {
            if (strictReadyWait && waitedReadyMs % 2000L < 32L) {
                val err = withContext(Dispatchers.Main) { exo.playerError }
                Log.i(
                    LOG_TAG,
                    "waiting STATE_READY waitedMs=$waitedReadyMs playbackState=${exo.playbackState} " +
                        "isLoading=${exo.isLoading} playWhenReady=${exo.playWhenReady} err=${err?.message}",
                )
            }
            delay(32)
            waitedReadyMs += 32
            if (waitedReadyMs >= maxReadyMs) {
                Log.w(
                    LOG_TAG,
                    "timeout waiting STATE_READY after ${waitedReadyMs}ms (strict=$strictReadyWait) " +
                        "state=${exo.playbackState} err=${exo.playerError?.message}",
                )
                break
            }
        }
        if (!isActive) {
            Log.d(LOG_TAG, "readyEffect cancelled before seek")
            return@LaunchedEffect
        }
        if (s.initialPositionMs > 0) {
            Log.d(LOG_TAG, "seekTo initialPositionMs=${s.initialPositionMs}")
            withContext(Dispatchers.Main) { exo.seekTo(s.initialPositionMs) }
            var n = 0
            while (isActive && n++ < 200) {
                val cur = withContext(Dispatchers.Main) { exo.currentPosition }
                if (abs(cur - s.initialPositionMs) < 1500) break
                delay(32)
            }
            Log.d(LOG_TAG, "after seek loop iterations=$n position=${exo.currentPosition}")
        }
        if (!isActive) return@LaunchedEffect
        if (released.get()) {
            Log.d(LOG_TAG, "readyEffect skip onPlaybackReady: already released")
            return@LaunchedEffect
        }
        val pos = withContext(Dispatchers.Main) { exo.currentPosition }
        val rate = withContext(Dispatchers.Main) { playbackRate() }
        Log.d(LOG_TAG, "onPlaybackReady positionMs=$pos rate=$rate")
        hooks.onPlaybackReady(pos, rate)
    }

    DisposableEffect(exo, instanceKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                scope.launch(Dispatchers.IO) {
                    mediaStateStore.upsertSpeed(instanceKey, parameters.speed)
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    DisposableEffect(exo, spec) {
        val s = spec
        val fb = s.audioFallbackFactory
        if (s.audioFallbackOnly && fb != null) {
            val fallbackSource = fb.create()
            val listener = exo.createAudioDecodeFallbackListener(
                logTag = OPEN_TUNE_PLAYER_LOG,
                mainHandler = mainHandler,
                media = AudioFallbackMedia.MediaSourcePayload(fallbackSource),
                onAudioDisabled = {
                    if (!s.audioDecodeUnsupportedBanner.isNullOrBlank()) {
                        showAudioUnsupportedBanner = true
                    }
                },
            )
            exo.addListener(listener)
            onDispose { exo.removeListener(listener) }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(exo, instanceKey, spec.hooks) {
        val s = spec
        val interval = s.hooks.progressIntervalMs()
        if (interval > 0L) {
            while (isActive) {
                delay(interval)
                if (!exo.isPlaying) continue
                if (released.get()) break
                val pos = exo.currentPosition
                hooksState.value.onProgressTick(pos, playbackRate())
                withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
            }
        } else {
            while (isActive) {
                delay(10_000L)
                if (released.get()) break
                if (exo.isPlaying) {
                    val pos = exo.currentPosition
                    withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
                }
            }
        }
    }

    BackHandler {
        when {
            isSubtitleAdjustActive -> {
                isSubtitleAdjustActive = false
                scope.launch(Dispatchers.IO) {
                    appConfigStore?.saveSubtitlePrefs(
                        com.opentune.storage.SubtitlePrefs(subtitleOffsetFraction, subtitleSizeScale),
                    )
                }
            }
            menuScreen != PlayerMenuScreen.None -> {
                if (menuScreen == PlayerMenuScreen.TopLevel) {
                    menuScreen = PlayerMenuScreen.None
                } else {
                    menuScreen = PlayerMenuScreen.TopLevel
                }
            }
            else -> {
                val pv = playerViewRef
                if (pv is OpenTuneTvPlayerView && pv.dismissSettingsPopupIfShowing()) {
                    return@BackHandler
                }
                if (pv != null && pv.isControllerFullyVisible) {
                    pv.hideController()
                } else {
                    scope.launch { shutdown(userInitiatedExit = true) }
                }
            }
        }
    }

    DisposableEffect(exo) {
        onDispose {
            runBlocking { shutdown(userInitiatedExit = false) }
        }
    }

    DisposableEffect(exo) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session =
            if (activity != null) {
                MediaSession.Builder(activity, exo).build()
            } else {
                null
            }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            session?.release()
        }
    }

    // --- Subtitle / settings derived state ---

    val subtitleOptions: List<SubtitleOption> = remember(spec.subtitleTracks, currentTracks, activeSubtitleTrackId) {
        val list = mutableListOf<SubtitleOption>()
        list.add(SubtitleOption.Off)
        if (spec.subtitleTracks.isNotEmpty()) {
            spec.subtitleTracks.forEach { list.add(SubtitleOption.FromSpec(it)) }
        } else {
            currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .forEach { list.add(SubtitleOption.ExoNative(it)) }
        }
        if (activeSubtitleTrackId != null) list.add(SubtitleOption.Adjust)
        list
    }

    val audioGroups: List<Tracks.Group> = remember(currentTracks) {
        currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    }

    // Log subtitle options whenever they are rebuilt
    LaunchedEffect(subtitleOptions) {
        Log.d(SUB_LOG_TAG, "subtitleOptions rebuilt: ${subtitleOptions.size} entries, activeId=$activeSubtitleTrackId, exoTextGroups=${currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }}")
    }

    // Sync speed picker index when speed menu is opened
    LaunchedEffect(menuScreen) {
        if (menuScreen == PlayerMenuScreen.Speed) {
            val cur = withContext(Dispatchers.Main) { exo.playbackParameters.speed }
            val idx = SPEED_VALUES.indexOfFirst { it == cur }
            if (idx >= 0) speedMenuIndex = idx
        }
    }

    val localContext = LocalContext.current
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val subtitleTranslationYPx = subtitleOffsetFraction * screenHeightPx

    // Step per DPAD press for offset/scale adjustments
    val offsetStep = 20f / screenHeightPx

    // --- Overlay callbacks (set on OpenTuneTvPlayerView via OpenTunePlayerView update block) ---

    val overlayNavCallback: (Int) -> Unit = { keyCode ->
        when (menuScreen) {
            PlayerMenuScreen.TopLevel -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> menuTopIndex = (menuTopIndex - 1 + 3) % 3
                KeyEvent.KEYCODE_DPAD_DOWN -> menuTopIndex = (menuTopIndex + 1) % 3
                KeyEvent.KEYCODE_DPAD_LEFT -> menuScreen = PlayerMenuScreen.None
                else -> {}
            }
            PlayerMenuScreen.Subtitles -> {
                val count = subtitleOptions.size
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> subtitleMenuIndex = (subtitleMenuIndex - 1 + count) % count
                    KeyEvent.KEYCODE_DPAD_DOWN -> subtitleMenuIndex = (subtitleMenuIndex + 1) % count
                    KeyEvent.KEYCODE_DPAD_LEFT -> menuScreen = PlayerMenuScreen.TopLevel
                    else -> {}
                }
            }
            PlayerMenuScreen.Audio -> {
                val count = audioGroups.size + 1
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> audioMenuIndex = (audioMenuIndex - 1 + count) % count
                    KeyEvent.KEYCODE_DPAD_DOWN -> audioMenuIndex = (audioMenuIndex + 1) % count
                    KeyEvent.KEYCODE_DPAD_LEFT -> menuScreen = PlayerMenuScreen.TopLevel
                    else -> {}
                }
            }
            PlayerMenuScreen.Speed -> {
                val count = SPEED_VALUES.size
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> speedMenuIndex = (speedMenuIndex - 1 + count) % count
                    KeyEvent.KEYCODE_DPAD_DOWN -> speedMenuIndex = (speedMenuIndex + 1) % count
                    KeyEvent.KEYCODE_DPAD_LEFT -> menuScreen = PlayerMenuScreen.TopLevel
                    else -> {}
                }
            }
            PlayerMenuScreen.None -> {}
        }
    }

    val overlaySelectCallback: () -> Unit = {
        when (menuScreen) {
            PlayerMenuScreen.TopLevel -> {
                menuScreen = when (menuTopIndex) {
                    0 -> { subtitleMenuIndex = 0; PlayerMenuScreen.Subtitles }
                    1 -> { audioMenuIndex = 0; PlayerMenuScreen.Audio }
                    2 -> PlayerMenuScreen.Speed
                    else -> PlayerMenuScreen.None
                }
            }
            PlayerMenuScreen.Subtitles -> {
                val option = subtitleOptions.getOrNull(subtitleMenuIndex)
                when (option) {
                    SubtitleOption.Off -> {
                        Log.d(SUB_LOG_TAG, "select: Off — disabling text track type")
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                        activeSubtitleTrackId = null
                        scope.launch(Dispatchers.IO) { mediaStateStore.upsertSubtitleTrack(instanceKey, null) }
                        menuScreen = PlayerMenuScreen.None
                    }
                    SubtitleOption.Adjust -> {
                        menuScreen = PlayerMenuScreen.None
                        isSubtitleAdjustActive = true
                    }
                    is SubtitleOption.ExoNative -> {
                        val gid = "exo_${option.group.mediaTrackGroup.id}"
                        Log.d(SUB_LOG_TAG, "select: ExoNative gid=$gid groupId=${option.group.mediaTrackGroup.id} isSupported=${option.group.isSupported}")
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, 0))
                            .build()
                        Log.d(SUB_LOG_TAG, "select: ExoNative params applied — disabledTypes=${exo.trackSelectionParameters.disabledTrackTypes}")
                        activeSubtitleTrackId = gid
                        scope.launch(Dispatchers.IO) { mediaStateStore.upsertSubtitleTrack(instanceKey, gid) }
                        menuScreen = PlayerMenuScreen.None
                    }
                    is SubtitleOption.FromSpec -> {
                        val track = option.track
                        if (track.externalRef == null) {
                            // Embedded — try direct TrackSelectionOverride first; fall back to language preference
                            val exoGroup = currentTracks.groups
                                .filter { it.type == C.TRACK_TYPE_TEXT }
                                .firstOrNull { g ->
                                    g.mediaTrackGroup.id == track.trackId
                                }
                            Log.d(SUB_LOG_TAG, "select: FromSpec embedded trackId=${track.trackId} lang=${track.language} label=${track.label} exoGroupMatch=${
                                exoGroup?.let { g -> "id=${g.mediaTrackGroup.id} isSupported=${g.isSupported}" } ?: "null"
                            }")
                            val params = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            if (exoGroup != null) {
                                Log.d(SUB_LOG_TAG, "select: using TrackSelectionOverride for group id=${exoGroup.mediaTrackGroup.id}")
                                params.setOverrideForType(TrackSelectionOverride(exoGroup.mediaTrackGroup, 0))
                            } else {
                                Log.d(SUB_LOG_TAG, "select: no matching ExoPlayer group found — falling back to preferredTextLanguage=${track.language}")
                                if (track.language != null) params.setPreferredTextLanguage(track.language)
                            }
                            exo.trackSelectionParameters = params.build()
                            Log.d(SUB_LOG_TAG, "select: params applied disabledTypes=${exo.trackSelectionParameters.disabledTrackTypes}")
                            activeSubtitleTrackId = track.trackId
                            scope.launch(Dispatchers.IO) { mediaStateStore.upsertSubtitleTrack(instanceKey, track.trackId) }
                        } else {
                            // External — resolve URI and re-prepare
                            menuScreen = PlayerMenuScreen.None
                            val externalRef = track.externalRef!!
                            Log.d(SUB_LOG_TAG, "select: FromSpec external trackId=${track.trackId} externalRef=$externalRef")
                            scope.launch {
                                val subtitleUri = if (externalRef.startsWith("http://") || externalRef.startsWith("https://")) {
                                    Uri.parse(externalRef)
                                } else {
                                    spec.resolveExternalSubtitle?.invoke(externalRef)
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
                                    .Factory(DefaultDataSource.Factory(localContext))
                                    .createMediaSource(subtitleConfig, C.TIME_UNSET)
                                val mergedSource = MergingMediaSource(spec.mediaSourceFactory.create(), subtitleSource)
                                withContext(Dispatchers.Main) {
                                    // One-shot listener: after MergingMediaSource tracks are resolved,
                                    // explicitly select the last supported text group (the sidecar).
                                    var sidecarListener: Player.Listener? = null
                                    sidecarListener = object : Player.Listener {
                                        override fun onTracksChanged(tracks: Tracks) {
                                            val supported = tracks.groups.filter {
                                                it.type == C.TRACK_TYPE_TEXT && it.isSupported
                                            }
                                            Log.d(SUB_LOG_TAG, "sidecar.onTracksChanged: textSupported=${supported.size} total=${tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }}")
                                            if (supported.isNotEmpty()) {
                                                exo.removeListener(this)
                                                val sidecarGroup = supported.last()
                                                Log.d(SUB_LOG_TAG, "sidecar: selecting group id=${sidecarGroup.mediaTrackGroup.id} label=${if (sidecarGroup.length > 0) sidecarGroup.getTrackFormat(0).label else null}")
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
                                            Log.d(SUB_LOG_TAG, "sidecar.onPlaybackStateChanged: state=$state")
                                            // Clean up if player goes back to IDLE unexpectedly
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
                                Log.d(SUB_LOG_TAG, "select: external re-prepared with merged source, activeSubtitleTrackId=${track.trackId}")
                                activeSubtitleTrackId = track.trackId
                                withContext(Dispatchers.IO) { mediaStateStore.upsertSubtitleTrack(instanceKey, track.trackId) }
                            }
                        }
                        if (track.externalRef == null) menuScreen = PlayerMenuScreen.None
                    }
                    null -> {}
                }
            }
            PlayerMenuScreen.Audio -> {
                if (audioMenuIndex == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                } else {
                    val group = audioGroups.getOrNull(audioMenuIndex - 1)
                    if (group != null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                            .build()
                    }
                }
                menuScreen = PlayerMenuScreen.None
            }
            PlayerMenuScreen.Speed -> {
                val speed = SPEED_VALUES.getOrElse(speedMenuIndex) { 1f }
                exo.playbackParameters = PlaybackParameters(speed)
                scope.launch(Dispatchers.IO) { mediaStateStore.upsertSpeed(instanceKey, speed) }
                menuScreen = PlayerMenuScreen.None
            }
            PlayerMenuScreen.None -> {}
        }
    }

    val subtitleAdjustCallback: (Int) -> Unit = { keyCode ->
        Log.d(SUB_LOG_TAG, "adjustCallback keyCode=$keyCode before: offsetFraction=$subtitleOffsetFraction sizeScale=$subtitleSizeScale")
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> subtitleOffsetFraction -= offsetStep
            KeyEvent.KEYCODE_DPAD_DOWN -> subtitleOffsetFraction += offsetStep
            KeyEvent.KEYCODE_DPAD_LEFT -> subtitleSizeScale = (subtitleSizeScale - 0.1f).coerceAtLeast(0.3f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> subtitleSizeScale = (subtitleSizeScale + 0.1f).coerceAtMost(3f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                isSubtitleAdjustActive = false
                scope.launch(Dispatchers.IO) {
                    appConfigStore?.saveSubtitlePrefs(
                        com.opentune.storage.SubtitlePrefs(subtitleOffsetFraction, subtitleSizeScale),
                    )
                }
            }
            else -> {}
        }
    }

    val bannerText = spec.audioDecodeUnsupportedBanner
    val topBanner: (@Composable BoxScope.() -> Unit)? =
        if (showAudioUnsupportedBanner && !bannerText.isNullOrBlank()) {
            {
                M3Text(
                    text = bannerText,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
        } else {
            null
        }

    Box(modifier = Modifier.fillMaxSize()) {
        OpenTunePlayerView(
            player = exo,
            modifier = Modifier.fillMaxSize(),
            onPlayerViewBound = { playerViewRef = it },
            onSettingsMenu = { menuScreen = PlayerMenuScreen.TopLevel; menuTopIndex = 0 },
            isOverlayActive = menuScreen != PlayerMenuScreen.None,
            overlayNavCallback = overlayNavCallback,
            overlaySelectCallback = overlaySelectCallback,
            isSubtitleAdjustActive = isSubtitleAdjustActive,
            subtitleAdjustCallback = subtitleAdjustCallback,
            subtitleTranslationYPx = subtitleTranslationYPx,
            subtitleSizeScale = subtitleSizeScale,
        )
        topBanner?.invoke(this@Box)
        if (menuScreen != PlayerMenuScreen.None) {
            PlayerSettingsOverlay(
                menuScreen = menuScreen,
                menuTopIndex = menuTopIndex,
                subtitleOptions = subtitleOptions,
                subtitleMenuIndex = subtitleMenuIndex,
                audioGroups = audioGroups,
                audioMenuIndex = audioMenuIndex,
                speedMenuIndex = speedMenuIndex,
                currentTracks = currentTracks,
            )
        }
        if (isSubtitleAdjustActive) {
            SubtitleAdjustOverlay()
        }
    }
}
