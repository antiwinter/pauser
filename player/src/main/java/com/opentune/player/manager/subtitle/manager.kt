package com.opentune.player.manager.subtitle

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.res.stringResource
import com.opentune.player.EntryStateKeys
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession
import com.opentune.player.manager.PlaybackManager
import com.opentune.player.manager.PlayerMenuEntry

private const val SUB_LOG_TAG = "OT_Subtitle"

// ---------------------------------------------------------------------------
// Session extensions: selectSubtitle, applyTextParams, updateSubtitleTrackId, armSidecarRecovery
// ---------------------------------------------------------------------------

/** Persists the subtitle track id into the spec's state and entry state. */
@UnstableApi
internal fun PlaybackSession.updateSubtitleTrackId(trackId: String?) {
    updateState { it.copy(subtitleTrackId = trackId) }
    notifyEntryState(EntryStateKeys.SUBTITLE_TRACK_ID, trackId)
}

/**
 * Selects a subtitle: null = off, an external track → attach its sidecar and rebuild, an embedded
 * track → a parameter-only selection. Text overrides are applied via [buildUpon] so audio/video
 * selections in the same [TrackSelectionParameters] are preserved.
 */
@UnstableApi
fun PlaybackSession.selectSubtitle(trackId: String?) {
    updateSubtitleTrackId(trackId)
    exo.trackSelectionParameters = applyTextParams(trackId)
    Log.d(SUB_LOG_TAG, "selectSubtitle: trackId=$trackId")
    if (currentSpec?.savedSubtitleTrack(trackId)?.externalRef != null) {
        armSidecarRecovery()
        rebuildKeepingPosition()
    }
}

/**
 * Applies text track selection for [trackId] onto the current [TrackSelectionParameters] via
 * [buildUpon], preserving audio/video overrides. Shared by [selectSubtitle] (runtime) and
 * [prepare] (initial load, before tracks are known). Always resets text overrides / preferred
 * language / undetermined-selection first so no stale selection leaks between entries or between
 * successive subtitle picks.
 *   null            → text disabled
 *   external sidecar → undetermined-language auto-select (the merged sidecar is the only text track)
 *   embedded, group resolved → explicit override
 *   embedded, group not yet loaded → preferred language (if known) + undetermined
 */
@UnstableApi
internal fun PlaybackSession.applyTextParams(trackId: String?): TrackSelectionParameters {
    val b = exo.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setPreferredTextLanguage(null)
        .setSelectUndeterminedTextLanguage(false)
    if (trackId == null) {
        return b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    val saved = currentSpec?.savedSubtitleTrack(trackId)
    if (saved?.externalRef != null) {
        return b.setSelectUndeterminedTextLanguage(true).build()
    }
    val groupId = trackId.removePrefix("exo_")
    val group = tracksFlow.value.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .firstOrNull { it.mediaTrackGroup.id == groupId }
    if (group != null) {
        return b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }
    saved?.language?.let { b.setPreferredTextLanguage(it) }
    return b.setSelectUndeterminedTextLanguage(true).build()
}

/**
 * After an external subtitle is selected, a sidecar load failure surfaces as a player error.
 * Drop the saved track, re-derive video-only text params, and rebuild without the sidecar.
 */
@UnstableApi
private fun PlaybackSession.armSidecarRecovery() {
    val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            exo.removeListener(this)
            Log.w(SUB_LOG_TAG, "Subtitle sidecar failed (code=${error.errorCode}), replaying without it")
            updateSubtitleTrackId(null)
            exo.trackSelectionParameters = applyTextParams(null)
            rebuildKeepingPosition()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) exo.removeListener(this)
        }
    }
    exo.addListener(listener)
}

// ---------------------------------------------------------------------------
// SubtitleManager
// ---------------------------------------------------------------------------

/**
 * Session-owned manager for subtitle track selection + the subtitle/adjust menus. Composes a
 * [SubtitleAdjust] for adjust-mode state and key handling. Lives in [PlaybackSession.managers] so
 * [onPrepare] restores the saved text track and resets adjust state per entry.
 */
@UnstableApi
internal class SubtitleManager(
    private val session: PlaybackSession,
) : PlaybackManager {
    val adjust = SubtitleAdjust(session)

    override fun onPrepare() {
        val state = session.currentSpec?.state
        adjust.reset(state)
        session.exo.trackSelectionParameters = session.applyTextParams(state?.subtitleTrackId)
    }

    /** Re-apply subtitle offset/scale/style now that the PlayerView is attached. The reset during
     *  [onPrepare] may race view inflation, so the surface re-applies on each `update`. */
    override fun onViewUpdate() {
        adjust.applyStyle()
    }

    override val menuEntries = listOf(
        PlayerMenuEntry(
            label = @Composable { stringResource(R.string.player_settings_subtitles) },
            children = ::buildSubtitleChildren,
            isSelected = { false },
            onSelect = {},
        ),
        PlayerMenuEntry(
            label = @Composable { stringResource(R.string.subtitle_adjust_mode_label) },
            children = { emptyList() },
            isSelected = { adjust.isActive },
            onSelect = { adjust.activate() },
        ),
    )

    private fun buildSubtitleChildren(): List<PlayerMenuEntry> {
        val spec = session.currentSpec ?: return emptyList()
        val source = spec.sources.getOrNull(spec.state.sourceIndex) ?: return emptyList()
        val tracks = session.tracksFlow.value
        val activeId = spec.state.subtitleTrackId
        val entries = mutableListOf<PlayerMenuEntry>()

        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.subtitle_track_none) },
            children = { emptyList() },
            isSelected = { activeId == null },
            onSelect = {
                Log.d(SUB_LOG_TAG, "select: Off")
                session.selectSubtitle(null)
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
                    isSelected = { activeId == track.trackId },
                    onSelect = {
                        Log.d(SUB_LOG_TAG, "select: FromSpec trackId=${track.trackId}")
                        session.selectSubtitle(track.trackId)
                    },
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
                        isSelected = { activeId == gid },
                        onSelect = {
                            Log.d(SUB_LOG_TAG, "select: ExoNative gid=$gid")
                            session.selectSubtitle(gid)
                        },
                    )
                }
        }

        return entries
    }
}
