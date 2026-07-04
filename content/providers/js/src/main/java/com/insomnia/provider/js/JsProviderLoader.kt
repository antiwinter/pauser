package com.insomnia.provider.js

import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.InsomniaProviderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JsProviderLoader : InsomniaProviderLoader {
    override suspend fun load(register: (InsomniaProvider) -> Unit) {
        val assets = ContextHolder.get().assets
        val hostApis = HostApis()
        val bundleFiles = withContext(Dispatchers.IO) {
            assets.list("")?.filter { it.endsWith(".js") } ?: emptyList()
        }
        for (bundleFile in bundleFiles) {
            val bundle = withContext(Dispatchers.IO) {
                assets.open(bundleFile).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val provider = JsProvider.create(assetPath = bundleFile, jsBundle = bundle, hostApis = hostApis)
            register(provider)
        }
    }
}
