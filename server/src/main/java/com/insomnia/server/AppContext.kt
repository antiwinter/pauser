package com.insomnia.server

import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.server.debug.JarBridge
import com.insomnia.storage.AppPrefsStore
import com.insomnia.storage.EndpointDao
import com.insomnia.storage.EndpointEntity
import com.insomnia.storage.EntryStateStore
import com.insomnia.storage.ProxyDao
import java.io.File

/**
 * App-level dependency surface exposed to the `:server` module.
 *
 * Implement this interface in `:app` and pass it to [InsomniaServer]. The debug route
 * package consumes it internally — `:app` has no need to import anything from `server.debug`.
 */
interface AppContext {
    fun getProviders(): List<InsomniaProvider>
    fun getProvider(protocol: String): InsomniaProvider?
    suspend fun getClient(endpointId: String): EndpointClient?
    suspend fun registerClient(endpointId: String, entity: EndpointEntity): EndpointClient?
    val endpointDao: EndpointDao
    val proxyDao: ProxyDao
    val entryStateStore: EntryStateStore
    val appConfigStore: AppPrefsStore
    val jarBridge: JarBridge
    val cacheDir: File
}
