package com.opentune.app

import android.app.Application
import android.media.MediaCodecList
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import com.opentune.content.contract.OpenTuneProviderRegistry
import com.opentune.proxy.contract.ProxyProviderRegistry
import com.opentune.app.providers.EndpointClientRegistry
import com.opentune.server.AppContext
import com.opentune.server.OpenTuneServer
import com.opentune.storage.EndpointEntity
import com.opentune.content.contract.PlatformCapabilities
import com.opentune.content.contract.PlatformInfoHolder
import com.opentune.content.contract.StreamRegistrarHolder
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.proxy.contract.ProxyProviderRegistryHolder
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings
import com.opentune.storage.EntryStateStore
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

    lateinit var proxyProviderRegistry: ProxyProviderRegistry
        private set

    lateinit var endpointClientRegistry: EndpointClientRegistry
        private set

    lateinit var openTuneServer: OpenTuneServer
        private set

    /** Shared disk cache — one instance, one size limit, correct LRU across all image loaders. */
    val sharedDiskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(File(cacheDir, "coil").toOkioPath())
            .maxSizeBytes(200L * 1024 * 1024)
            .build()
    }

    val imageLoader: ImageLoader by lazy { buildImageLoader() }

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { buildImageLoader() }
        database = Room.databaseBuilder<OpenTuneDatabase>(
            context = this,
            name = getDatabasePath("opentune.db").absolutePath,
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
        storageBindings = OpenTuneStorageBindings(
            endpointDao = database.endpointDao(),
            entryStateStore = EntryStateStore(database),
            appConfigStore = AppPrefs(applicationContext),
            proxyDao = database.proxyDao(),
        )
        val platformInfo = AndroidPlatformInfo(this)
        PlatformInfoHolder.set(platformInfo)
        providerRegistry = OpenTuneProviderRegistry()
        proxyProviderRegistry = ProxyProviderRegistry.discover()
        proxyProviderRegistry.allProxies().forEach { it.bootstrap(platformInfo) }
        endpointClientRegistry = EndpointClientRegistry(
            endpointDao = storageBindings.endpointDao,
            providerRegistry = providerRegistry,
            proxyDao = storageBindings.proxyDao,
            proxyProviderRegistry = proxyProviderRegistry,
            sharedDiskCache = sharedDiskCache,
            appContext = this,
        )
        StorageBindingsHolder.set(storageBindings)
        EndpointClientRegistryHolder.set(endpointClientRegistry)
        OpenTuneProviderRegistryHolder.set(providerRegistry)
        ProxyProviderRegistryHolder.set(proxyProviderRegistry)
        openTuneServer = OpenTuneServer(
            appContext = object : AppContext {
                override fun getProviders() = providerRegistry.providersFlow.value
                override fun getProvider(protocol: String) = runCatching { providerRegistry.provider(protocol) }.getOrNull()
                override fun platformCapabilities() = providerRegistry.platformCapabilities
                override suspend fun getClient(endpointId: String) = endpointClientRegistry.getOrCreate(endpointId)
                override suspend fun registerClient(endpointId: String, entity: EndpointEntity) = endpointClientRegistry.registerHandle(endpointId, entity)
                override val endpointDao get() = storageBindings.endpointDao
                override val entryStateStore get() = storageBindings.entryStateStore
                override val appConfigStore get() = storageBindings.appConfigStore
            },
        )
        StreamRegistrarHolder.set(openTuneServer)
        appScope.launch(Dispatchers.IO) { openTuneServer.start() }
        appScope.launch(Dispatchers.IO) { providerRegistry.setCapabilities(buildPlatformCapabilities()) }
        appScope.launch { providerRegistry.discoverAsync() }
    }

    private fun buildImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()

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
