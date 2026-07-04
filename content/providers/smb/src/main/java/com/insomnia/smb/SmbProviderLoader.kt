package com.insomnia.smb

import com.insomnia.content.contract.InsomniaProvider
import com.insomnia.content.contract.InsomniaProviderLoader

class SmbProviderLoader : InsomniaProviderLoader {
    override suspend fun load(register: (InsomniaProvider) -> Unit) {
        register(SmbProvider())
    }
}
