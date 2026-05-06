package com.opentune.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.appConfigDataStore by preferencesDataStore(name = "opentune_app_config")

data class SubtitlePrefs(
    val offsetFraction: Float = 0f,
    val sizeScale: Float = 1f,
)

class DataStoreAppConfigStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Draft fields ---

    private fun draftKey(providerId: String) = stringPreferencesKey("draft_$providerId")

    suspend fun loadDraft(providerId: String): Map<String, String> {
        val prefs = context.appConfigDataStore.data.first()
        val raw = prefs[draftKey(providerId)] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrElse { emptyMap() }
    }

    suspend fun saveDraft(providerId: String, values: Map<String, String>) {
        context.appConfigDataStore.edit { prefs ->
            prefs[draftKey(providerId)] = json.encodeToString<Map<String, String>>(values)
        }
    }

    suspend fun clearDraft(providerId: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs.remove(draftKey(providerId))
        }
    }

    // --- Subtitle prefs (global) ---

    private val subtitleOffsetKey = floatPreferencesKey("subtitle_offset_fraction")
    private val subtitleSizeKey = floatPreferencesKey("subtitle_size_scale")

    suspend fun loadSubtitlePrefs(): SubtitlePrefs {
        val prefs = context.appConfigDataStore.data.first()
        return SubtitlePrefs(
            offsetFraction = prefs[subtitleOffsetKey] ?: 0f,
            sizeScale = prefs[subtitleSizeKey] ?: 1f,
        )
    }

    suspend fun saveSubtitlePrefs(prefs: SubtitlePrefs) {
        context.appConfigDataStore.edit {
            it[subtitleOffsetKey] = prefs.offsetFraction
            it[subtitleSizeKey] = prefs.sizeScale
        }
    }
}
