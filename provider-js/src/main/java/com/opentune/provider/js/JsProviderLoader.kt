package com.opentune.provider.js

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader

class JsProviderLoader : OpenTuneProviderLoader {
    override fun load(register: (OpenTuneProvider) -> Unit) {
        val assets = AssetManagerHolder.get()
        val hostApis = HostApis()
        assets.list("")
            ?.filter { it.endsWith("-provider.js") }
            ?.forEach { bundleFile ->
                val bundle = assets.open(bundleFile).use { it.readBytes().toString(Charsets.UTF_8) }
                register(JsProvider(assetPath = bundleFile, jsBundle = bundle, hostApis = hostApis))
            }
    }
}
