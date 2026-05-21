package com.opentune.storage

data class OpenTuneStorageBindings(
    val endpointDao: EndpointDao,
    val entryStateStore: EntryStateStore,
    val appConfigStore: AppConfigStore,
    val proxyConfigDao: ProxyConfigDao,
    val proxyAssignmentDao: ProxyAssignmentDao,
)
