package com.opentune.app

import android.app.Application
import android.media.MediaCodecList
import com.opentune.app.providers.OpenTuneProviderRegistry
import com.opentune.app.providers.ProviderInstanceRegistry
import com.opentune.provider.CodecCapabilities
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings
import com.opentune.storage.RoomMediaStateStore
import com.opentune.storage.thumb.ThumbnailDiskCache
import java.io.File

class OpenTuneApplication : Application() {

    lateinit var database: OpenTuneDatabase
        private set

    lateinit var storageBindings: OpenTuneStorageBindings
        private set

    lateinit var providerRegistry: OpenTuneProviderRegistry
        private set

    lateinit var instanceRegistry: ProviderInstanceRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        database = OpenTuneDatabase.create(getDatabasePath("opentune.db").absolutePath)
        storageBindings = OpenTuneStorageBindings(
            serverDao = database.serverDao(),
            mediaStateStore = RoomMediaStateStore(database),
            appConfigStore = DataStoreAppConfigStore(applicationContext),
            thumbnailDiskCache = ThumbnailDiskCache(File(cacheDir, "covers")),
        )
        providerRegistry = OpenTuneProviderRegistry.discover()
        instanceRegistry = ProviderInstanceRegistry(
            serverDao = storageBindings.serverDao,
            providerRegistry = providerRegistry,
        )
        val platformContext = AndroidPlatformContext(this)
        providerRegistry.allProviders().forEach { it.bootstrap(platformContext) }
        providerRegistry.setCapabilities(buildCodecCapabilities())
    }

    private fun buildCodecCapabilities(): CodecCapabilities {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val videoMimes = mutableListOf<String>()
        val audioMimes = mutableListOf<String>()
        var maxPixels = 0
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val caps = info.getCapabilitiesForType(mime)
                if (mime.startsWith("video/")) {
                    videoMimes += mime
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        val w = vc.supportedWidths.upper
                        val h = vc.supportedHeights.upper
                        if (w * h > maxPixels) maxPixels = w * h
                    }
                } else if (mime.startsWith("audio/")) {
                    audioMimes += mime
                }
            }
        }
        return CodecCapabilities(
            supportedVideoMimeTypes = videoMimes.distinct(),
            supportedAudioMimeTypes = audioMimes.distinct(),
            maxVideoPixels = maxPixels.coerceAtLeast(1920 * 1080),
            supportedSubtitleFormats = listOf("srt", "ass", "ssa", "vtt", "webvtt"),
        )
    }
}
