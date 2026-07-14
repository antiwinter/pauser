package com.insomnia.app

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import com.insomnia.content.contract.InsomniaProviderRegistry
import com.insomnia.proxy.contract.ProxyProviderRegistry
import com.insomnia.app.providers.EndpointClientRegistry
import com.insomnia.server.AppContext
import com.insomnia.server.InsomniaServer
import com.insomnia.storage.EndpointEntity

import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.InsomniaProviderRegistryHolder
import com.insomnia.proxy.contract.ProxyProviderRegistryHolder
import com.insomnia.storage.StorageBindingsHolder
import com.insomnia.storage.InsomniaDatabase
import com.insomnia.storage.InsomniaStorageBindings
import com.insomnia.storage.EntryStateStore
import com.insomnia.provider.js.HostApis
import com.insomnia.provider.js.JarLoader
import com.insomnia.server.debug.JarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File

class InsomniaApplication : Application() {

    /** App-level scope for background work tied to the process lifetime. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var database: InsomniaDatabase
        private set

    lateinit var storageBindings: InsomniaStorageBindings
        private set

    lateinit var providerRegistry: InsomniaProviderRegistry
        private set

    lateinit var proxyProviderRegistry: ProxyProviderRegistry
        private set

    lateinit var endpointClientRegistry: EndpointClientRegistry
        private set

    lateinit var insomniaServer: InsomniaServer
        private set

    /** Shared JAR loader for the debug JAR bridge — separate from per-engine loaders. */
    private val debugSandbox: File by lazy { File(cacheDir, "providers/debug").apply { mkdirs() } }
    private val debugJarLoader: JarLoader by lazy { JarLoader(debugSandbox, OkHttpClient()) }
    private val debugJarBridge: JarBridge by lazy { JarBridgeImpl(debugJarLoader, HostApis("debug", debugSandbox)) }

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
        Timber.plant(InsomniaDebugTree())
        SingletonImageLoader.setSafe { imageLoader }
        database = Room.databaseBuilder<InsomniaDatabase>(
            context = this,
            name = getDatabasePath("insomnia.db").absolutePath,
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
        storageBindings = InsomniaStorageBindings(
            endpointDao = database.endpointDao(),
            entryStateStore = EntryStateStore(database),
            appConfigStore = AppPrefs(applicationContext),
            proxyDao = database.proxyDao(),
        )

        providerRegistry = InsomniaProviderRegistry()
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
        InsomniaProviderRegistryHolder.set(providerRegistry)
        ProxyProviderRegistryHolder.set(proxyProviderRegistry)
        val appContext = object : AppContext {
            override fun getProviders() = providerRegistry.providersFlow.value
            override fun getProvider(protocol: String) = runCatching { providerRegistry.provider(protocol) }.getOrNull()
            override suspend fun getClient(endpointId: String) = endpointClientRegistry.getOrCreate(endpointId)
            override suspend fun registerClient(endpointId: String, entity: EndpointEntity) = endpointClientRegistry.registerHandle(endpointId, entity)
            override val endpointDao get() = storageBindings.endpointDao
            override val proxyDao get() = storageBindings.proxyDao
            override val entryStateStore get() = storageBindings.entryStateStore
            override val appConfigStore get() = storageBindings.appConfigStore
            override val jarBridge get() = debugJarBridge
            override val cacheDir get() = this@InsomniaApplication.cacheDir
        }
        insomniaServer = InsomniaServer(appContext = appContext)
        appScope.launch(Dispatchers.IO) { insomniaServer.start() }
        appScope.launch { providerRegistry.discoverAsync() }
    }

    private fun buildImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache(sharedDiskCache)
            .build()
}
