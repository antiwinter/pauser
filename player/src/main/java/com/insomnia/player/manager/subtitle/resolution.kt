package com.insomnia.player.manager.subtitle

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.insomnia.player.PlaybackSpec
import com.insomnia.player.SubtitleTrack

internal fun subtitleMimeType(ref: String): String {
    val path = ref.substringBefore('?')
    return when (path.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private val ABSPOS_SUBTITLES = setOf(
    MimeTypes.APPLICATION_PGS,
    MimeTypes.APPLICATION_VOBSUB,
    MimeTypes.APPLICATION_DVBSUBS,
    MimeTypes.TEXT_SSA
)

// Servers deliver PGS tracks as `application/x-media3-cues` in sampleMimeType; the real format
// (`application/pgs`) is only in codecs — so both fields must be checked.
internal fun Format?.isAdjustableSubtitle(): Boolean {
    if (this == null) return false
    return sampleMimeType !in ABSPOS_SUBTITLES &&
        codecs !in ABSPOS_SUBTITLES
}

internal fun PlaybackSpec.findSubtitleTrack(savedId: String?): SubtitleTrack? {
    if (savedId == null) return null
    return sources.getOrNull(state.sourceIndex)?.subtitleTracks?.find { it.trackId == savedId }
}

// Language left undetermined so setSelectUndeterminedTextLanguage auto-selects it (matches the reselect path).
internal fun SubtitleTrack.toSidecarConfig(): MediaItem.SubtitleConfiguration? {
    val ref = externalRef ?: return null
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(ref))
        .setMimeType(subtitleMimeType(ref))
        .build()
}

// "longlang [script] [(complementary…)]", e.g. chs&eng/zh -> "Chinese simplified (en)".
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
