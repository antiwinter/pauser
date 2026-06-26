package com.opentune.player.manager.subtitle

import android.text.SpannableStringBuilder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.res.stringResource
import com.opentune.player.EntryStateKeys
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession
import com.opentune.player.manager.PlaybackManager
import com.opentune.player.manager.PlayerMenuEntry
import com.opentune.player.manager.detectFormat
import java.util.Locale

private const val SUB_LOG_TAG = "OT_Subtitle"

/** Sentinel for [SubtitleManager.appliedSubtitleId] meaning "not yet applied this entry",
 *  distinct from `null` (the auto subtitle id). */
private object NotAppliedYet

// ---------------------------------------------------------------------------
// Session extension: applyTextParams (the unified subtitle selector)
// ---------------------------------------------------------------------------

/**
 * The single entry point for subtitle selection. Builds text [TrackSelectionParameters] for
 * [trackId], persists it into spec state + entry state, and — for an external sidecar picked at
 * runtime — rebuilds the media source to attach it.
 *
 * trackId conventions:
 *   null          → "auto": follow device locale (preferred language + undetermined)
 *   "off"         → text track disabled
 *   "exo_<gid>"   → embedded: explicit override on the resolved text group
 *   <other>       → external sidecar (looked up via [PlaybackSpec.findSubtitleTrack])
 *
 * [rebuild] gates the external-rebuild path: the initial restore ([onPrepare]) and the
 * [onTracksChanged] embedded re-apply pass `rebuild = false` since the media source already carries
 * the sidecar / only a parameter change is needed. Runtime menu selection uses the default `true`.
 */
@UnstableApi
internal fun PlaybackSession.applyTextParams(trackId: String?): TrackSelectionParameters {
    val b = exo.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setPreferredTextLanguage(null)
        .setSelectUndeterminedTextLanguage(true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

    when {
        trackId == null -> {
            // auto: prefer device locale, allow undetermined fallback
            b.setPreferredTextLanguage(Locale.getDefault().language)
        }
        trackId == "off" -> {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        trackId.startsWith("exo_") -> {
            val groupId = trackId.removePrefix("exo_")
            val group = tracksFlow.value.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .firstOrNull { it.mediaTrackGroup.id == groupId }
            if (group != null) {
                b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }
        else -> {
           // nothing
        }
    }

    // Persist before any rebuild so toMediaSource() sees the new subtitleTrackId.
    updateState { it.copy(subtitleTrackId = trackId) }
    notifyEntryState(EntryStateKeys.SUBTITLE_TRACK_ID, trackId)
    return b.build()
}

// ---------------------------------------------------------------------------
// SubtitleManager
// ---------------------------------------------------------------------------

/**
 * Session-owned manager for subtitle track selection + the subtitle/adjust menus.
 *
 * The saved subtitle selection is applied from the player lifecycle, not the Compose layer:
 *   - [onPrepare] applies auto/off/external (the sidecar is already in the initial media source,
 *     so no rebuild).
 *   - [onTracksChanged] applies an embedded ("exo_") override once the text group has loaded.
 *
 * Sidecar-failure recovery is a permanent [Player.Listener] that, when a sidecar is active and the
 * error is a parsing failure, drops the subtitle and rebuilds without it. Renderer errors are left
 * to [com.opentune.player.manager.FallbackManager].
 */
@UnstableApi
internal class SubtitleManager(
    private val session: PlaybackSession,
) : PlaybackManager {
    val cc = CC(session)

    /** Last subtitleTrackId applied via [onTracksChanged]; skip re-apply when unchanged to avoid
     *  re-persisting on every track change. [NotAppliedYet] distinguishes "not yet applied this
     *  entry" from null (the auto id). Reset in [onPrepare]. */
    private var appliedSubtitleId: Any? = NotAppliedYet

    override fun onPrepare() {
        val state = session.currentSpec?.state
        cc.reset(state)
        appliedSubtitleId = NotAppliedYet
        // sidecar restoration already handled in initial toMediaSource() build
    }

    /** Re-apply the SubtitleView visibility toggle now that the PlayerView is attached. The reset
     *  during [onPrepare] may race view inflation, so the surface re-applies on each `update`. */
    override fun onViewUpdate() {
        cc.refresh()
    }

    override val listeners: List<Player.Listener> = listOf(object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            cc.isActive = tracks.detectFormat(C.TRACK_TYPE_TEXT).isAdjustableSubtitle()
            cc.refresh()
            buildMenuEntries()
            val id = session.currentSpec?.state?.subtitleTrackId ?: return
            if (id == appliedSubtitleId) return
            if (id.startsWith("exo_") && // no text yet
                !tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) return
            session.exo.trackSelectionParameters = session.applyTextParams(id)
            appliedSubtitleId = id
        }

        override fun onCues(cueGroup: CueGroup) {
            // Join every non-blank text cue so nothing is dropped. A SpannableStringBuilder
            // preserves each cue's spans (italic/bold/color) across the '\n' join; the common
            // single-cue case passes the original Spanned through untouched.
            val texts = cueGroup.cues.mapNotNull { it.text }.filter { it.isNotBlank() }
            val combined: CharSequence? = when {
                texts.isEmpty() -> null
                texts.size == 1 -> texts[0]
                else -> SpannableStringBuilder().apply {
                    texts.forEachIndexed { i, t -> if (i > 0) append("\n"); append(t) }
                }
            }
            cc.setCueText(combined)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Only when a sidecar subtitle is currently active and the failure is a parse error.
            if (!error.isParsingError()) return

            val spec = session.currentSpec ?: return
            val id = spec.state.subtitleTrackId
            if (spec.findSubtitleTrack(id)?.externalRef == null) return

            Log.w(SUB_LOG_TAG, "Subtitle sidecar failed (code=${error.errorCode}), replaying without it")
            session.exo.trackSelectionParameters = session.applyTextParams(null)
            session.rebuildKeepingPosition()
        }
    })

    /** Hidden entirely when the entry has no subtitle tracks (only the implicit "Off" would show).
     *  The Adjust entry is additionally hidden for image (PGS/VobSub/DVB) and ASS tracks, where a
     *  global offset/scale either can't apply (bitmap cues) or fights author-controlled positioning. */
    override val menuEntries: List<PlayerMenuEntry>
        get() {
            if (session.currentSpec == null) return emptyList()
            if (_menuEntries.size < 2) return emptyList()
            val entries = mutableListOf(
                PlayerMenuEntry(
                    label = @Composable { stringResource(R.string.player_settings_subtitles) },
                    children = { _menuEntries },
                    isSelected = { false },
                    onSelect = {},
                ),
            )
            if (cc.isActive) {
                entries += PlayerMenuEntry(
                    label = @Composable { stringResource(R.string.subtitle_adjust_mode_label) },
                    children = { emptyList() },
                    isSelected = { !cc.isLocked },
                    onSelect = { cc.unlock() },
                )
            }
            return entries
        }

    /**
     * The subtitle submenu contents. Rebuilt by [buildMenuEntries] from lifecycle hooks
     * ([onPrepare] for the initial/external-only state, [Player.Listener.onTracksChanged] once
     * exo reports text groups) and otherwise returned as-is by the entry's `children` lambda —
     * so opening/navigating the menu never rebuilds. [isSelected] lambdas read `subtitleTrackId`
     * live, so the `●` indicator updates without a rebuild.
     */
    private var _menuEntries: List<PlayerMenuEntry> = emptyList()

    private fun buildMenuEntries(): List<PlayerMenuEntry> {
        val spec = session.currentSpec ?: run { _menuEntries = emptyList(); return emptyList() }
        val source = spec.sources.getOrNull(spec.state.sourceIndex) ?: run {
            _menuEntries = emptyList(); return emptyList()
        }
        val tracks = session.tracksFlow.value

        val externalTracks = source.subtitleTracks.filter {
            Log.d(SUB_LOG_TAG, it.toString())
            it.externalRef != null }

        val entries = mutableListOf<PlayerMenuEntry>()

        entries += PlayerMenuEntry(
            label = @Composable { stringResource(R.string.subtitle_track_none) },
            children = { emptyList() },
            isSelected = { session.currentSpec?.state?.subtitleTrackId == "off" },
            onSelect = {
                session.exo.trackSelectionParameters = session.applyTextParams("off")
            },
        )

        // embedded — keep only tracks a renderer can decode.
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
            .forEach { group ->
                val fmt = group.getTrackFormat(0)
                Log.w(SUB_LOG_TAG, "embedded subtitle group ${group.mediaTrackGroup.id} label=${fmt.label} lang=${fmt.language} mime=${fmt.sampleMimeType} codecs=${fmt.codecs} w=${fmt.width} h=${fmt.height} container=${fmt.containerMimeType} roleFlags=${fmt.roleFlags} supported=${group.isTrackSupported(0)} fmt=${fmt}")
                val gid = "exo_${group.mediaTrackGroup.id}"
                val label = buildSubtitleName(fmt.label, fmt.language)
                entries += PlayerMenuEntry(
                    label = @Composable { label },
                    children = { emptyList() },
                    isSelected = { session.currentSpec?.state?.subtitleTrackId == gid },
                    onSelect = {
                        session.exo.trackSelectionParameters = session.applyTextParams(gid)
                    },
                )
            }

        // external (sidecar) — only tracks with an externalRef are selectable here
        externalTracks.forEach { track ->
            val label = buildSubtitleName(track.label, track.language)
            entries += PlayerMenuEntry(
                label = @Composable { label },
                children = { emptyList() },
                isSelected = { session.currentSpec?.state?.subtitleTrackId == track.trackId },
                onSelect = {
                    session.exo.trackSelectionParameters = session.applyTextParams(track.trackId)
                    session.rebuildKeepingPosition()
                },
            )
        }

        // Hide the submenu when only "Off" is available.
        val result = if (entries.size < 2) emptyList() else entries
        _menuEntries = result
        return result
    }
}

/** True for parse-stage failures (the category a sidecar subtitle load/parse error falls into). */
private fun PlaybackException.isParsingError(): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> true
    else -> false
}
