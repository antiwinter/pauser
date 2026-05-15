package com.opentune.smb

import com.opentune.provider.OpenTuneProvider
import com.opentune.provider.OpenTuneProviderLoader

class SmbProviderLoader : OpenTuneProviderLoader {
    override suspend fun load(register: (OpenTuneProvider) -> Unit) {
        register(SmbProvider())
    }
}
