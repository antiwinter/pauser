package com.opentune.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opentune.storage.AppConfigStore
import com.opentune.storage.SubtitlePrefs
import com.opentune.storage.TitleLang
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.appConfigDataStore by preferencesDataStore(name = "opentune_app_config")

class DataStoreAppConfigStore(private val context: Context) : AppConfigStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun draftKey(providerId: String) = stringPreferencesKey("draft_$providerId")

    override suspend fun loadDraft(providerId: String): Map<String, String> {
        val prefs = context.appConfigDataStore.data.first()
        val raw = prefs[draftKey(providerId)] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrElse { emptyMap() }
    }

    override suspend fun saveDraft(providerId: String, values: Map<String, String>) {
        context.appConfigDataStore.edit { prefs ->
            prefs[draftKey(providerId)] = json.encodeToString<Map<String, String>>(values)
        }
    }

    override suspend fun clearDraft(providerId: String) {
        context.appConfigDataStore.edit { prefs -> prefs.remove(draftKey(providerId)) }
    }

    private val subtitleOffsetKey = floatPreferencesKey("subtitle_offset_fraction")
    private val subtitleSizeKey = floatPreferencesKey("subtitle_size_scale")

    override suspend fun loadSubtitlePrefs(): SubtitlePrefs {
        val prefs = context.appConfigDataStore.data.first()
        return SubtitlePrefs(
            offsetFraction = prefs[subtitleOffsetKey] ?: 0f,
            sizeScale = prefs[subtitleSizeKey] ?: 1f,
        )
    }

    override suspend fun saveSubtitlePrefs(prefs: SubtitlePrefs) {
        context.appConfigDataStore.edit {
            it[subtitleOffsetKey] = prefs.offsetFraction
            it[subtitleSizeKey] = prefs.sizeScale
        }
    }

    private val titleLangKey = stringPreferencesKey("title_lang")

    override val titleLangFlow: Flow<TitleLang>
        get() = context.appConfigDataStore.data.map { prefs ->
            when (prefs[titleLangKey]) {
                TitleLang.Original.name -> TitleLang.Original
                else -> TitleLang.Local
            }
        }

    override suspend fun saveTitleLang(value: TitleLang) {
        context.appConfigDataStore.edit { prefs -> prefs[titleLangKey] = value.name }
    }

    private val preBufferMsKey = intPreferencesKey("pre_buffer_ms")

    override val preBufferMsFlow: Flow<Int>
        get() = context.appConfigDataStore.data.map { prefs ->
            prefs[preBufferMsKey] ?: AppConfigStore.DEFAULT_PRE_BUFFER_MS
        }

    override suspend fun savePreBufferMs(ms: Int) {
        context.appConfigDataStore.edit { it[preBufferMsKey] = ms }
    }
}
