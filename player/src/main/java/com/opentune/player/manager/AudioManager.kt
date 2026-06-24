package com.opentune.player.manager

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import com.opentune.player.EntryStateKeys
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession

private const val AUDIO_LOG_TAG = "OT_Audio"

// ---------------------------------------------------------------------------
// Session extensions: applyAudioParams, updateAudioTrackId, selectAudio
// ---------------------------------------------------------------------------

/** Persists the audio track id into the spec's state and entry state. */
@UnstableApi
internal fun PlaybackSession.updateAudioTrackId(trackId: String?) {
    updateState { it.copy(audioTrackId = trackId) }
    notifyEntryState(EntryStateKeys.AUDIO_TRACK_ID, trackId)
}

/**
 * Applies audio track selection for [trackId] onto the current [TrackSelectionParameters] via
 * [buildUpon], preserving text/video overrides. null = auto (clear override), else the group
 * behind [trackId] ("audio_<id>"). Shared by [selectAudio] (runtime) and [prepare] (initial load).
 */
@UnstableApi
fun PlaybackSession.applyAudioParams(trackId: String?): TrackSelectionParameters {
    val b = exo.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
    if (trackId != null) {
        val groupId = trackId.removePrefix("audio_")
        tracksFlow.value.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .firstOrNull { it.mediaTrackGroup.id == groupId }
            ?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
    }
    return b.build()
}

/** Selects audio: null = auto (clear override), else the group behind [trackId] ("audio_<id>"). */
@UnstableApi
fun PlaybackSession.selectAudio(trackId: String?) {
    Log.d(AUDIO_LOG_TAG, "selectAudio: trackId=$trackId")
    exo.trackSelectionParameters = applyAudioParams(trackId)
    updateAudioTrackId(trackId)
}

// ---------------------------------------------------------------------------
// Track label builder
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Session-owned manager: audio track selection + menu
// ---------------------------------------------------------------------------

@UnstableApi
internal class AudioManager(
    private val session: PlaybackSession,
) : PlaybackManager {

    // Apply the saved audio override whenever tracks change. At prepare time tracks are empty so
    // this only clears stale overrides; once tracks load it resolves the group and sets the override.
    // Idempotent against the saved id (the user's last choice), so re-applying on every callback is safe.
    override val listeners: List<Player.Listener> = listOf(object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val id = session.currentSpec?.state?.audioTrackId ?: return
            session.exo.trackSelectionParameters = session.applyAudioParams(id)
        }
    })

    override val menuEntries: List<PlayerMenuEntry> = listOf(
        PlayerMenuEntry(
            label = @Composable { stringResource(R.string.player_settings_audio) },
            children = ::buildAudioChildren,
            isSelected = { false },
            onSelect = {},
        )
    )

    private fun buildAudioChildren(): List<PlayerMenuEntry> {
        val activeId = session.currentSpec?.state?.audioTrackId
        val audioGroups = session.tracksFlow.value.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val entries = mutableListOf<PlayerMenuEntry>()

        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.player_audio_auto) },
            children = { emptyList() },
            isSelected = { activeId == null },
            onSelect = {
                Log.d(AUDIO_LOG_TAG, "select audio: Auto")
                session.selectAudio(null)
            },
        )

        audioGroups.forEachIndexed { index, group ->
            val label = buildAudioGroupLabel(group, index)
            val gid = "audio_${group.mediaTrackGroup.id}"
            entries += PlayerMenuEntry(
                label = @Composable { label },
                children = { emptyList() },
                isSelected = { activeId == gid },
                onSelect = {
                    Log.d(AUDIO_LOG_TAG, "select audio: gid=$gid")
                    session.selectAudio(gid)
                },
            )
        }

        return entries
    }
}
