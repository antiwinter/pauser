package com.opentune.server

import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.OpenTuneProvider
import com.opentune.server.debug.JarBridge
import com.opentune.storage.AppPrefsStore
import com.opentune.storage.EndpointDao
import com.opentune.storage.EndpointEntity
import com.opentune.storage.EntryStateStore

/**
 * App-level dependency surface exposed to the `:server` module.
 *
 * Implement this interface in `:app` and pass it to [OpenTuneServer]. The debug route
 * package consumes it internally — `:app` has no need to import anything from `server.debug`.
 */
interface AppContext {
    fun getProviders(): List<OpenTuneProvider>
    fun getProvider(protocol: String): OpenTuneProvider?
    suspend fun getClient(endpointId: String): EndpointClient?
    suspend fun registerClient(endpointId: String, entity: EndpointEntity): EndpointClient?
    val endpointDao: EndpointDao
    val entryStateStore: EntryStateStore
    val appConfigStore: AppPrefsStore
    val jarBridge: JarBridge
}
