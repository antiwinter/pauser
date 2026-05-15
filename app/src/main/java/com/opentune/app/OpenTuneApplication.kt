package com.opentune.app

import android.app.Application
import android.media.MediaCodecList
import androidx.room.Room
import com.opentune.app.providers.OpenTuneProviderRegistry
import com.opentune.app.providers.ProviderInstanceRegistry
import com.opentune.app.server.OpenTuneServer
import com.opentune.provider.PlatformCapabilities
import com.opentune.provider.PlatformInfoHolder
import com.opentune.provider.StreamRegistrarHolder
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings
import com.opentune.storage.RoomMediaStateStore
import com.opentune.storage.thumb.ThumbnailDiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class OpenTuneApplication : Application() {

    /** App-level scope for background work tied to the process lifetime. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var database: OpenTuneDatabase
        private set

    lateinit var storageBindings: OpenTuneStorageBindings
        private set

    lateinit var providerRegistry: OpenTuneProviderRegistry
        private set

    lateinit var instanceRegistry: ProviderInstanceRegistry
        private set

    lateinit var openTuneServer: OpenTuneServer
        private set

    override fun onCreate() {
        super.onCreate()
        openTuneServer = OpenTuneServer()
        StreamRegistrarHolder.set(openTuneServer)
        appScope.launch(Dispatchers.IO) { openTuneServer.start() }
        database = Room.databaseBuilder<OpenTuneDatabase>(
            context = this,
            name = getDatabasePath("opentune.db").absolutePath,
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
        storageBindings = OpenTuneStorageBindings(
            serverDao = database.serverDao(),
            mediaStateStore = RoomMediaStateStore(database),
            appConfigStore = DataStoreAppConfigStore(applicationContext),
            thumbnailDiskCache = ThumbnailDiskCache(File(cacheDir, "covers")),
        )
        val platformInfo = AndroidPlatformInfo(this)
        PlatformInfoHolder.set(platformInfo)
        providerRegistry = OpenTuneProviderRegistry()
        instanceRegistry = ProviderInstanceRegistry(
            serverDao = storageBindings.serverDao,
            providerRegistry = providerRegistry,
        )
        appScope.launch(Dispatchers.IO) { providerRegistry.setCapabilities(buildPlatformCapabilities()) }
        appScope.launch { providerRegistry.discoverAsync() }
    }

    private fun buildPlatformCapabilities(): PlatformCapabilities {
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
        return PlatformCapabilities(
            videoMime = videoMimes.distinct(),
            audioMime = audioMimes.distinct(),
            maxPixels = maxPixels.coerceAtLeast(1920 * 1080),
            subtitleFormats = listOf("srt", "ass", "ssa", "vtt", "webvtt"),
        )
    }
}
