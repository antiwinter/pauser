package com.opentune.player.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
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
import com.opentune.player.PlayerStores
import com.opentune.player.R
import com.opentune.player.menu.PlayerMenuEntry
import com.opentune.storage.MediaStateKey
import com.opentune.storage.upsertAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
class AudioController(
    private val currentTracksState: MutableState<Tracks>,
    private val activeTrackIdState: MutableState<String?>,
    private val scope: CoroutineScope,
    private val stores: PlayerStores,
    private val mediaStateKey: MediaStateKey,
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
            isSelected = { activeTrackIdState.value == null },
            onSelect = {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
                activeTrackIdState.value = null
                scope.launch(Dispatchers.IO) {
                    stores.mediaStateStore.upsertAudioTrack(mediaStateKey, null)
                }
            },
        )

        audioGroups.forEachIndexed { index, group ->
            val label = buildAudioGroupLabel(group, index)
            val gid = "audio_${group.mediaTrackGroup.id}"
            entries += PlayerMenuEntry(
                label = @Composable { label },
                children = { emptyList() },
                isSelected = { activeTrackIdState.value == gid },
                onSelect = {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    activeTrackIdState.value = gid
                    scope.launch(Dispatchers.IO) {
                        stores.mediaStateStore.upsertAudioTrack(mediaStateKey, gid)
                    }
                },
            )
        }

        return entries
    }
}

@UnstableApi
@Composable
fun rememberAudioController(
    exo: ExoPlayer,
    stores: PlayerStores,
    mediaStateKey: MediaStateKey,
    initialTrackId: String?,
): AudioController {
    val scope = rememberCoroutineScope()
    val currentTracksState = remember { mutableStateOf(Tracks.EMPTY) }
    val activeTrackIdState = remember { mutableStateOf(initialTrackId) }

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

    return remember {
        AudioController(
            currentTracksState = currentTracksState,
            activeTrackIdState = activeTrackIdState,
            scope = scope,
            stores = stores,
            mediaStateKey = mediaStateKey,
            exo = exo,
        )
    }
}
