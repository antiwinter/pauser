package com.insomnia.content.contract

import com.insomnia.player.PlaybackSource
import kotlinx.serialization.Serializable
import com.insomnia.core.form.contract.FormFieldSpec
import com.insomnia.core.form.contract.QrResult
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.proxy.contract.WrappedProxyClient

// --- Endpoint-level validation result (extends core with domain fields) ---

sealed class EndpointValidationResult {
    data class Success(val fields: Map<String, String>) : EndpointValidationResult()
    data class Error(val message: String) : EndpointValidationResult()
}

// --- Streaming ---

interface ProviderStream {
    suspend fun getSize(): Long
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    fun close()
}

// --- Progressive entry emission ---

@Serializable
data class EntryEmission(
    val items: List<EntryInfo>,
    val totalCount: Int?,
    val isComplete: Boolean = false,
)

interface EntryEmitter {
    suspend fun emit(items: List<EntryInfo>, totalCount: Int? = null, isComplete: Boolean = false)
}

// --- Provider factory ---

interface InsomniaProvider {
    val protocol: String
    val providesArt: Boolean

    fun getFieldsSpec(): List<FormFieldSpec>
    fun createClient(values: Map<String, String>): EndpointClient
}

interface InsomniaProviderLoader {
    suspend fun load(register: (InsomniaProvider) -> Unit)
}

// --- Endpoint client ---

abstract class EndpointClient {
    open var imageLoader: coil3.ImageLoader? = null
    open var proxyClient: ProxyClient = WrappedProxyClient(null)
    open var endpointId: String = ""
    open var protocol: String = ""
    open val progressIntervalMs: Long = 10_000L
    open suspend fun test(): EndpointValidationResult = EndpointValidationResult.Success(emptyMap())
    var entryEmitter: EntryEmitter? = null
    abstract suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        options: QueryOptions = QueryOptions(),
    ): EntryList
    open suspend fun getPlaybackSources(itemRef: String, startMs: Long = 0L): List<PlaybackSource> = emptyList()
    abstract suspend fun getEntries(itemRefs: List<String>): EntryList
    open suspend fun getTaggedEntries(
        tag: EntryTag,
        scopeLocation: String? = null,
        startIndex: Int = 0,
        limit: Int = 20,
        sortBy: SortField? = null,
        sortOrder: SortOrder = SortOrder.Descending,
    ): EntryList = EntryList(emptyList(), 0)
    open suspend fun updateEntryState(itemRef: String, key: String, value: String?): Unit = Unit
    open suspend fun openStream(itemRef: String): ProviderStream? = null

    open suspend fun getQr(): QrResult.QrReady? = null
    open suspend fun pollQr(token: String): QrResult = QrResult.Error("not supported")
}
