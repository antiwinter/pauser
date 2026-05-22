package com.opentune.emby

import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.OpenTuneProviderLoader

class EmbyProviderLoader : OpenTuneProviderLoader {
    override suspend fun load(register: (OpenTuneProvider) -> Unit) {
        register(EmbyProvider())
    }
}
