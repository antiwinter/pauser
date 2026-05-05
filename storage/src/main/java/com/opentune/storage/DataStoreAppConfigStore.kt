package com.opentune.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.appConfigDataStore by preferencesDataStore(name = "opentune_app_config")

class DataStoreAppConfigStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

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
}
