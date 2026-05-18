package com.opentune.storage

data class OpenTuneStorageBindings(
    val sourceDao: SourceDao,
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: AppConfigStore,
)
