package com.insomnia.storage

data class InsomniaStorageBindings(
    val endpointDao: EndpointDao,
    val entryStateStore: EntryStateStore,
    val appConfigStore: AppPrefsStore,
    val proxyDao: ProxyDao,
)
