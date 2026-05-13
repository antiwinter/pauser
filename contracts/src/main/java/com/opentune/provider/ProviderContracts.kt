package com.opentune.provider

// --- Streaming ---

/**
 * Random-access byte-stream abstraction for local/network files.
 * No Closeable — the implementing [OpenTuneProviderInstance.withStream] owns the lifecycle.
 */
interface ItemStream {
    /** Reads [size] bytes from [position] into [buffer] at [offset]. Returns bytes actually read (0 = EOF). */
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    /** Total byte length of the stream. */
    suspend fun getSize(): Long
}

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
     * [hash] is used by the app to compute sourceId = "${protocol}_${hash}".
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
     * Returns [ValidationResult.Success] with a stable [hash] (used to compute sourceId)
     * and a human-readable [displayName], or [ValidationResult.Error].
     */
    suspend fun validateFields(values: Map<String, String>): ValidationResult

    /**
     * Construct a live instance from already-validated credentials.
     * Called without a sourceId; the instance carries no identity state.
     */
    fun createInstance(values: Map<String, String>, capabilities: CodecCapabilities): OpenTuneProviderInstance

}

// --- Provider instance ---

/**
 * Live protocol handle for a single configured server.
 * No identity fields — the app registry maps sourceId → instance externally.
 */
interface OpenTuneProviderInstance {
    suspend fun loadBrowsePage(location: String?, startIndex: Int, limit: Int): BrowsePageResult
    suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem>
    suspend fun loadDetail(itemRef: String): MediaDetailModel
    suspend fun resolvePlayback(itemRef: String, startMs: Long): PlaybackSpec

    /**
     * Opens a random-access stream for [itemRef], calls [block] with it, and closes the stream.
     * Returns null if this provider does not support streaming (default).
     */
    suspend fun <T> withStream(itemRef: String, block: suspend (ItemStream) -> T): T? = null
}
