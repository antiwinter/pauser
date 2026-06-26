package com.opentune.player.manager.subtitle

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.opentune.player.PlaybackSpec
import com.opentune.player.SubtitleTrack
import com.opentune.player.engine.PlaybackSession

/** Maps an external subtitle ref (URL/path) to a media3 MIME type by file extension. */
internal fun subtitleMimeType(ref: String): String {
    val path = ref.substringBefore('?')
    return when (path.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/** The saved subtitle track for [savedId] within the active source, or null if none/unmatched. */
internal fun PlaybackSpec.findSubtitleTrack(savedId: String?): SubtitleTrack? {
    if (savedId == null) return null
    return sources.getOrNull(state.sourceIndex)?.subtitleTracks?.find { it.trackId == savedId }
}

/** Resolves the MIME of the currently active subtitle track, or null when none is resolvable
 *  (auto with no selected text track yet, or subtitles off). */
@UnstableApi
internal fun PlaybackSession.isSubtitleAdjustable(): Boolean {
    val spec = currentSpec ?: return false
    val id = spec.state.subtitleTrackId
    val mime = trackInfoFlow.value.textMime
        ?: spec.findSubtitleTrack(id)?.externalRef?.let { subtitleMimeType(it) }
    return when (mime) {
        MimeTypes.APPLICATION_PGS,
        MimeTypes.APPLICATION_VOBSUB,
        MimeTypes.APPLICATION_DVBSUBS,
        MimeTypes.TEXT_SSA -> false
        else -> true
    }
}

/**
 * Builds a sidecar [MediaItem.SubtitleConfiguration] for an external subtitle track, or null for an
 * embedded track. Language is intentionally left undetermined so the player auto-selects the track
 * via `setSelectUndeterminedTextLanguage(true)` — the same selection the sidecar reselect path uses.
 */
internal fun SubtitleTrack.toSidecarConfig(): MediaItem.SubtitleConfiguration? {
    val ref = externalRef ?: return null
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(ref))
        .setMimeType(subtitleMimeType(ref))
        .build()
}

/**
 * Unified subtitle entry name: `longlang [script] [(complementary…)]`.
 *
 * `longlang` is the full language name derived from [lang]. The raw [rawLabel] (the Emby
 * `Title` for external tracks, or the exo `Format.label` for embedded ones) is split on `&`/
 * whitespace; each token is looked up in [LANG_MAP]. A script-bearing entry (`chs`/`cht`)
 * becomes the script modifier, an entry naming the primary language is dropped as redundant,
 * any other language entry contributes its short code, and tokens absent from the map
 * (`bd`, `hk`, `commentary`…) are folded in verbatim as the complementary list.
 *
 *   chs&eng / zh  -> "Chinese simplified (en)"
 *   BD cht HK / zh -> "Chinese traditional (bd, hk)"
 *   eng / en      -> "English"
 */
internal fun buildSubtitleName(rawLabel: String?, lang: String?): String {
    val primary = LANG_MAP[lang?.lowercase().orEmpty()]
    val longLang = when {
        primary != null -> primary.long
        lang.isNullOrBlank() || lang.equals("und", ignoreCase = true) -> "Unknown"
        else -> lang
    }
    var script: String? = primary?.script
    val complementary = mutableListOf<String>()
    rawLabel?.split(Regex("[&\\s]+"))
        ?.filter { it.isNotBlank() }
        ?.forEach { raw ->
            val entry = LANG_MAP[raw.lowercase()]
            when {
                entry == null -> complementary += raw.lowercase()
                entry.script != null -> script = entry.script
                primary != null && entry.short == primary.short -> Unit // redundant with longLang
                else -> complementary += entry.short
            }
        }
    val base = if (script != null) "$longLang $script" else longLang
    return if (complementary.isEmpty()) base else "$base (${complementary.joinToString(", ")})"
}

private data class LangEntry(val short: String, val long: String, val script: String? = null)

/**
 * Token → language lookup. Keys cover ISO 639-1/2/T codes plus the script-qualified variants
 * (`chs`/`cht`) and full English names. `short` is the canonical 2-letter code used for the
 * complementary list; `script` is non-null only for script variants of a language.
 */
private val LANG_MAP: Map<String, LangEntry> = buildMap {
    fun lang(short: String, long: String, vararg aliases: String) {
        put(short, LangEntry(short, long))
        for (a in aliases) put(a, LangEntry(short, long))
    }
    fun variant(canonical: String, long: String, script: String, vararg keys: String) {
        for (k in keys) put(k, LangEntry(canonical, long, script))
    }
    lang("zh", "Chinese", "chi", "zho", "chinese")
    variant("zh", "Chinese", "simplified", "chs", "simplified")
    variant("zh", "Chinese", "traditional", "cht", "traditional")
    lang("en", "English", "eng", "english")
    lang("ja", "Japanese", "jpn", "japanese")
    lang("ko", "Korean", "kor", "korean")
    lang("fr", "French", "fre", "fra", "french")
    lang("de", "German", "ger", "deu", "german")
    lang("es", "Spanish", "spa", "spanish")
    lang("it", "Italian", "ita", "italian")
    lang("pt", "Portuguese", "por", "portuguese")
    lang("ru", "Russian", "rus", "russian")
    lang("ar", "Arabic", "ara", "arabic")
    lang("th", "Thai", "tha", "thai")
    lang("vi", "Vietnamese", "vie", "vietnamese")
}
