package com.opentune.app

import android.app.Application
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

import com.opentune.content.contract.StreamRegistrarHolder
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.proxy.contract.ProxyProviderRegistryHolder
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.OpenTuneStorageBindings
import com.opentune.storage.EntryStateStore
import com.opentune.provider.js.HostApis
import com.opentune.provider.js.JarLoader
import com.opentune.server.debug.JarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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

    /** Shared JAR loader for the debug JAR bridge — separate from per-engine loaders. */
    private val debugJarLoader: JarLoader by lazy { JarLoader(OkHttpClient()) }
    private val debugJarBridge: JarBridge by lazy { JarBridgeImpl(debugJarLoader, HostApis()) }

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
        SingletonImageLoader.setSafe { imageLoader }
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

        providerRegistry = OpenTuneProviderRegistry()
        proxyProviderRegistry = ProxyProviderRegistry.discover()
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
                override suspend fun getClient(endpointId: String) = endpointClientRegistry.getOrCreate(endpointId)
                override suspend fun registerClient(endpointId: String, entity: EndpointEntity) = endpointClientRegistry.registerHandle(endpointId, entity)
                override val endpointDao get() = storageBindings.endpointDao
                override val entryStateStore get() = storageBindings.entryStateStore
                override val appConfigStore get() = storageBindings.appConfigStore
                override val jarBridge get() = debugJarBridge
            },
        )
        StreamRegistrarHolder.set(openTuneServer)
        appScope.launch(Dispatchers.IO) { openTuneServer.start() }
        appScope.launch { providerRegistry.discoverAsync() }
    }

    private fun buildImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache(sharedDiskCache)
            .build()
}
