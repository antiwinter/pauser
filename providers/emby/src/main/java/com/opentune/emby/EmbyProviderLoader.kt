package com.opentune.emby

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader

class EmbyProviderLoader : OpenTuneProviderLoader {
    override suspend fun load(register: (OpenTuneProvider) -> Unit) {
        register(EmbyProvider())
    }
}
