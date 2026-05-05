package com.opentune.provider

import android.content.Context

// --- Field specs (moved from ConfigContracts.kt) ---

data class ServerFieldSpec(
    val id: String,
    val labelKey: String,
    val kind: ServerFieldKind,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val order: Int = 0,
    val placeholderKey: String? = null,
)

enum class ServerFieldKind {
    Text,
    SingleLineText,
    Password,
}

// --- Validation result ---

sealed class ValidationResult {
    /**
     * Provider connected, authenticated, and derived a stable identity.
     * [fieldsJson] is the opaque credential blob to be stored in [com.opentune.storage.ServerEntity];
     * [hash] is used by the app to compute sourceId = "${providerType}_${hash}".
     */
    data class Success(
        val hash: String,
        val displayName: String,
        val fieldsJson: String,
    ) : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}

// --- Submit result (kept for UI layer) ---

sealed class SubmitResult {
    data object Success : SubmitResult()
    data class Error(val message: String) : SubmitResult()
}

// --- Provider factory ---

/**
 * Stateless factory registered in [com.opentune.app.providers.OpenTuneProviderRegistry].
 * Does not hold server state or store references.
 */
interface OpenTuneProvider {
    val providerType: String

    /** Single field spec for both add and edit forms. Does not include display_name. */
    fun getFieldsSpec(): List<ServerFieldSpec>

    /**
     * Connect, authenticate, and verify the supplied credentials.
     * Returns [ValidationResult.Success] with a stable [hash] (used to compute sourceId)
     * and a human-readable [displayName], or [ValidationResult.Error].
     */
    suspend fun validateFields(values: Map<String, String>): ValidationResult

    /**
     * Construct a live instance from already-validated credentials.
     * Called without a sourceId; the instance carries no identity state.
     */
    fun createInstance(values: Map<String, String>): OpenTuneProviderInstance

    /** One-time bootstrap (e.g. install HTTP client identification). */
    fun bootstrap(context: Context) {}
}

// --- Provider instance ---

/**
 * Live protocol handle for a single configured server.
 * No identity fields — the app registry maps sourceId → instance externally.
 */
interface OpenTuneProviderInstance {
    suspend fun loadBrowsePage(location: String, startIndex: Int, limit: Int): BrowsePageResult
    suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem>
    suspend fun loadDetail(itemRef: String): MediaDetailModel
    suspend fun resolvePlayback(itemRef: String, startMs: Long, context: Context): PlaybackSpec
}
