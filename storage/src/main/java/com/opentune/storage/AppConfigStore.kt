package com.opentune.storage

import kotlinx.coroutines.flow.Flow

data class SubtitlePrefs(
    val offsetFraction: Float = 0f,
    val sizeScale: Float = 1f,
)

enum class TitleLang { Local, Original }

interface AppConfigStore {
    suspend fun loadDraft(providerId: String): Map<String, String>
    suspend fun saveDraft(providerId: String, values: Map<String, String>)
    suspend fun clearDraft(providerId: String)
    suspend fun loadSubtitlePrefs(): SubtitlePrefs
    suspend fun saveSubtitlePrefs(prefs: SubtitlePrefs)
    val titleLangFlow: Flow<TitleLang>
    suspend fun saveTitleLang(value: TitleLang)
    val preBufferMsFlow: Flow<Int>
    suspend fun savePreBufferMs(ms: Int)

    companion object {
        const val DEFAULT_PRE_BUFFER_MS = 5 * 60 * 1000
    }
}
