package com.opentune.server

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlatformCapabilities
import com.opentune.storage.AppConfigStore
import com.opentune.storage.SourceDao
import com.opentune.storage.SourceEntity
import com.opentune.storage.UserMediaStateStore

/**
 * App-level dependency surface exposed to the `:server` module.
 *
 * Implement this interface in `:app` and pass it to [OpenTuneServer]. The debug route
 * package consumes it internally — `:app` has no need to import anything from `server.debug`.
 */
interface AppContext {
    fun getProviders(): List<OpenTuneProvider>
    fun getProvider(protocol: String): OpenTuneProvider?
    fun platformCapabilities(): PlatformCapabilities
    suspend fun getInstance(sourceId: String): OpenTuneProviderInstance?
    suspend fun createAndRegister(sourceId: String, entity: SourceEntity): OpenTuneProviderInstance?
    val sourceDao: SourceDao
    val mediaStateStore: UserMediaStateStore
    val appConfigStore: AppConfigStore
}
