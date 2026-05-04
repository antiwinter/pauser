package com.opentune.smb

import android.content.Context
import com.opentune.provider.CatalogBindingPlugin
import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.PlaybackResolveDeps
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.ProviderConfigBackend
import com.opentune.provider.ServerFieldSpec

class SmbProvider : OpenTuneProvider {

    override val providerId: String = OpenTuneProviderIds.FILE_SHARE

    private val catalogPluginImpl = SmbCatalogBindingPlugin()
    private val configBackendImpl = SmbConfigBackend()

    override fun addFields(): List<ServerFieldSpec> = SmbServerFields.serverFields()

    override fun editFields(): List<ServerFieldSpec> = SmbServerFields.serverFields()

    override val catalogPlugin: CatalogBindingPlugin get() = catalogPluginImpl

    override val configBackend: ProviderConfigBackend get() = configBackendImpl

    override suspend fun resolvePlayback(
        deps: PlaybackResolveDeps,
        sourceId: Long,
        itemRef: String,
        startMs: Long,
    ): PlaybackSpec = SmbPlaybackResolver.resolve(deps, sourceId, itemRef, startMs)

    override fun bootstrap(context: Context) {
        // File-share has no global HTTP client identification.
    }
}
