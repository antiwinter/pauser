package com.opentune.smb

import com.opentune.content.contract.OpenTuneProvider
import com.opentune.content.contract.OpenTuneProviderLoader

class SmbProviderLoader : OpenTuneProviderLoader {
    override suspend fun load(register: (OpenTuneProvider) -> Unit) {
        register(SmbProvider())
    }
}
