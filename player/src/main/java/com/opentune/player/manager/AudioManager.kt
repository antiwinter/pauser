package com.opentune.player.manager

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
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession
import kotlinx.coroutines.CoroutineScope

private const val AUDIO_LOG_TAG = "OT_Audio"

@UnstableApi
internal fun buildAudioGroupLabel(group: Tracks.Group, index: Int): String {
    if (group.length == 0) return "Audio ${index + 1}"
    val fmt = group.getTrackFormat(0)
    val channels = if (fmt.channelCount > 0) " (${fmt.channelCount}ch)" else ""
    return when {
        !fmt.label.isNullOrBlank() -> fmt.label!! + channels
        !fmt.language.isNullOrBlank() -> fmt.language!! + channels
        else -> "Audio ${index + 1}$channels"
    }
}

@UnstableApi
internal class AudioManager(
    private val currentTracksState: MutableState<Tracks>,
    private val activeTrackId: State<String?>,
    private val scope: CoroutineScope,
    private val session: PlaybackSession,
    private val exo: ExoPlayer,
) {
    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = @Composable { stringResource(R.string.player_settings_audio) },
        children = ::buildAudioChildren,
        isSelected = { false },
        onSelect = {},
    )

    private fun buildAudioChildren(): List<PlayerMenuEntry> {
        val audioGroups = currentTracksState.value.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val entries = mutableListOf<PlayerMenuEntry>()

        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.player_audio_auto) },
            children = { emptyList() },
            isSelected = { activeTrackId.value == null },
            onSelect = {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
                Log.d(AUDIO_LOG_TAG, "select audio: Auto")
                session.updateAudioTrackId(null)
            },
        )

        audioGroups.forEachIndexed { index, group ->
            val label = buildAudioGroupLabel(group, index)
            val gid = "audio_${group.mediaTrackGroup.id}"
            entries += PlayerMenuEntry(
                label = @Composable { label },
                children = { emptyList() },
                isSelected = { activeTrackId.value == gid },
                onSelect = {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    Log.d(AUDIO_LOG_TAG, "select audio: gid=$gid")
                    session.updateAudioTrackId(gid)
                },
            )
        }

        return entries
    }
}

@UnstableApi
@Composable
internal fun rememberAudioManager(
    exo: ExoPlayer,
    session: PlaybackSession,
): AudioManager {
    val scope = rememberCoroutineScope()
    val currentTracksState = remember { mutableStateOf(Tracks.EMPTY) }
    val activeTrackId = session.audioTrackIdFlow.collectAsState()

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

    return remember(exo, session) {
        AudioManager(
            currentTracksState = currentTracksState,
            activeTrackId = activeTrackId,
            scope = scope,
            session = session,
            exo = exo,
        )
    }
}
