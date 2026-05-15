package com.opentune.provider

// --- Streaming ---

/**
 * Random-access byte-stream for a single provider resource.
 * Callers **must** call [close] when done; the stream does not participate in any outer lifecycle.
 * Used exclusively by [OpenTuneServer] route handlers — never imported by player or UI code.
 */
interface ProviderStream {
    /** Total byte length of the resource. */
    suspend fun getSize(): Long
    /** Reads [size] bytes from [position] into [buffer] at [offset]. Returns bytes actually read (0 = EOF). */
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    /** Closes the underlying connection / file handle. */
    fun close()
}


// --- Field specs (moved from ConfigContracts.kt) ---
enum class ServerFieldKind {
    Text,
    SingleLineText,
    Password,
}

data class ServerFieldSpec(
    val id: String,
    val labelKey: String,
    val kind: ServerFieldKind,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val order: Int = 0,
    val placeholderKey: String? = null,
)

// --- Validation result ---

sealed class ValidationResult {
    /**
     * Provider connected, authenticated, and derived a stable identity.
     * [fields] is the merged credential map the **app** serializes into [com.opentune.storage.ServerEntity.fieldsJson];
     * [hash] is used by the app to compute sourceId = "${protocol}_${hash}".
     */
    data class Success(
        val hash: String,
        val name: String,
        val fields: Map<String, String>,
    ) : ValidationResult()

    data class Error(val message: String) : ValidationResult()
}

// --- Provider factory ---

/**
 * Stateless factory registered in [com.opentune.app.providers.OpenTuneProviderRegistry].
 * Does not hold server state or store references.
 */
interface OpenTuneProvider {
    val protocol: String

    /**
     * True if catalog list items carry HTTP cover art directly (e.g. Emby).
     * False if covers must be extracted from the media stream (e.g. SMB).
     */
    val providesCover: Boolean

    /** Single field spec for both add and edit forms. Does not include display_name. */
    fun getFieldsSpec(): List<ServerFieldSpec>

    /**
     * Connect, authenticate, and verify the supplied credentials.
     * Returns [ValidationResult.Success] with [hash], human-readable [name], and [fields] to persist,
     * or [ValidationResult.Error].
     */
    suspend fun validateFields(values: Map<String, String>): ValidationResult

    /**
     * Construct a live instance from already-validated credentials.
     * Called without a sourceId; the instance carries no identity state.
     */
    fun createInstance(values: Map<String, String>, capabilities: PlatformCapabilities): OpenTuneProviderInstance
}

/**
 * Service-loader entry point for a backend module.
 * Each module (Kotlin or JS) implements this to register its provider(s) into the registry.
 * Use [META-INF/services/com.opentune.provider.OpenTuneProviderLoader] to auto-discover.
 */
interface OpenTuneProviderLoader {
    suspend fun load(register: (OpenTuneProvider) -> Unit)
}

// --- Provider instance ---

/**
 * Live protocol handle for a single configured server.
 * No identity fields — the app registry maps sourceId → instance externally.
 */
interface OpenTuneProviderInstance {
    suspend fun listEntry(location: String?, startIndex: Int, limit: Int): EntryList
    suspend fun search(scopeLocation: String, query: String): List<EntryInfo>
    suspend fun getDetail(itemRef: String): EntryDetail
    suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec

    /**
     * Opens a random-access [ProviderStream] for [itemRef].
     * Returns null if this provider does not support direct byte streaming (default — Emby, JS).
     * The caller ([OpenTuneServer]) is responsible for calling [ProviderStream.close].
     */
    suspend fun openStream(itemRef: String): ProviderStream? = null

}
