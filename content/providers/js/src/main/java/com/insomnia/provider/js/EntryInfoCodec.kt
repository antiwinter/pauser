package com.insomnia.provider.js

import com.insomnia.content.contract.EntryEmission
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Decodes the JSON JS providers return into contract types ([EntryInfo], [EntryList],
 * [EntryEmission]). All three are [kotlinx.serialization.Serializable] and the TS
 * types mirror the Kotlin field names exactly, so decoding is plain
 * [decodeFromJsonElement] with no hand-mirrored field mapping.
 *
 * Strict by design: a malformed element in a list is dropped (returns null,
 * filtered by the caller's `mapNotNull`); a malformed standalone entry throws,
 * surfacing the provider bug instead of silently producing a half-built entry.
 *
 * [sanitize] drops playback sources with empty urls from each entry's `sources` —
 * the one piece of real post-decode logic.
 */
internal object EntryInfoCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseEntryList(obj: JsonObject): EntryList? = runCatching {
        val list = json.decodeFromJsonElement<EntryList>(obj)
        list.copy(items = list.items.map(::sanitize))
    }.getOrNull()

    fun parseEntryArray(arr: JsonArray): List<EntryInfo> =
        arr.mapNotNull { parseEntry(it.jsonObject) }

    /** Decodes an `emit-entries` notification result into an [EntryEmission]. */
    fun parseEmission(obj: JsonObject?): EntryEmission? {
        if (obj == null) return null
        return runCatching {
            val e = json.decodeFromJsonElement<EntryEmission>(obj)
            e.copy(items = e.items.map(::sanitize))
        }.getOrNull()
    }

    fun parseEntry(obj: JsonObject): EntryInfo? = runCatching {
        sanitize(json.decodeFromJsonElement<EntryInfo>(obj))
    }.getOrNull()

    private fun sanitize(info: EntryInfo): EntryInfo {
        val srcs = info.sources ?: return info
        val filtered = srcs.filter { it.url.isNotEmpty() }
        return if (filtered.size == srcs.size) info else info.copy(sources = filtered)
    }
}
