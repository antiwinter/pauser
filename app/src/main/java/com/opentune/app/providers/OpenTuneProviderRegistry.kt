package com.opentune.app.providers

import com.opentune.app.OpenTuneApplication
import com.opentune.app.playback.PlaybackPreparer
import com.opentune.app.providers.emby.EmbyCatalogFactory
import com.opentune.app.providers.emby.EmbyPlaybackPreparer
import com.opentune.app.providers.smb.SmbCatalogFactory
import com.opentune.app.providers.smb.SmbPlaybackPreparer
import com.opentune.storage.OpenTuneDatabase

class OpenTuneProviderRegistry internal constructor(
    private val catalogPlugins: Map<String, CatalogBindingPlugin>,
    private val playbackPreparers: Map<String, PlaybackPreparer>,
) {
    fun catalogPlugin(providerId: String): CatalogBindingPlugin =
        catalogPlugins[providerId] ?: error("Unknown catalog provider: $providerId")

    fun playbackPreparer(providerId: String): PlaybackPreparer =
        playbackPreparers[providerId] ?: error("Unknown playback provider: $providerId")

    companion object {
        fun create(): OpenTuneProviderRegistry {
            val httpLibrary = object : CatalogBindingPlugin {
                override suspend fun browseBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    locationDecoded: String,
                ) = EmbyCatalogFactory.bindingForBrowse(app, sourceId, locationDecoded)

                override suspend fun searchBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    scopeDecoded: String,
                ) = EmbyCatalogFactory.bindingForSearch(app, sourceId, scopeDecoded)

                override suspend fun detailBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    itemRefDecoded: String,
                ) = EmbyCatalogFactory.bindingForDetail(app, sourceId)
            }
            val fileShare = object : CatalogBindingPlugin {
                override suspend fun browseBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    locationDecoded: String,
                ) = SmbCatalogFactory.bindingForBrowse(database, sourceId, locationDecoded)

                override suspend fun searchBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    scopeDecoded: String,
                ) = SmbCatalogFactory.bindingForSearch(database, sourceId, scopeDecoded)

                override suspend fun detailBinding(
                    app: OpenTuneApplication,
                    database: OpenTuneDatabase,
                    sourceId: Long,
                    itemRefDecoded: String,
                ) = SmbCatalogFactory.bindingForDetail(database, sourceId, itemRefDecoded)
            }
            return OpenTuneProviderRegistry(
                catalogPlugins = mapOf(
                    OpenTuneProviderIds.HTTP_LIBRARY to httpLibrary,
                    OpenTuneProviderIds.FILE_SHARE to fileShare,
                ),
                playbackPreparers = mapOf(
                    OpenTuneProviderIds.HTTP_LIBRARY to EmbyPlaybackPreparer,
                    OpenTuneProviderIds.FILE_SHARE to SmbPlaybackPreparer,
                ),
            )
        }
    }
}
