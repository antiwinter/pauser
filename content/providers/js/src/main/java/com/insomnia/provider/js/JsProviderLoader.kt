package com.insomnia.provider.js

import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.InsomniaProviderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JsProviderLoader : InsomniaProviderLoader {
    override suspend fun load(register: (InsomniaProvider) -> Unit) {
        val assets = ContextHolder.get().assets
        val cacheDir = ContextHolder.get().cacheDir
        val bundleFiles = withContext(Dispatchers.IO) {
            assets.list("")?.filter { it.endsWith(".js") } ?: emptyList()
        }
        for (bundleFile in bundleFiles) {
            val providerName = bundleFile.removeSuffix(".js")
            val sandboxRoot = java.io.File(cacheDir, "providers/$providerName").apply { mkdirs() }
            val hostApis = HostApis(providerName, sandboxRoot)
            val bundle = withContext(Dispatchers.IO) {
                assets.open(bundleFile).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val provider = JsProvider.create(assetPath = bundleFile, jsBundle = bundle, hostApis = hostApis)
            register(provider)
        }
    }
}
