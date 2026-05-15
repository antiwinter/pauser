package com.opentune.provider.js

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JsProviderLoader : OpenTuneProviderLoader {
    override suspend fun load(register: (OpenTuneProvider) -> Unit) {
        val assets = AssetManagerHolder.get()
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
