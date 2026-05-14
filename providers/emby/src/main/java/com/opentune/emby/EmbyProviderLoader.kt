package com.opentune.emby

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader

class EmbyProviderLoader : OpenTuneProviderLoader {
    override fun load(register: (OpenTuneProvider) -> Unit) {
        register(EmbyProvider())
    }
}
