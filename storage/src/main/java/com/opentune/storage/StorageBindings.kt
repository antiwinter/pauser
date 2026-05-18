package com.opentune.storage

data class OpenTuneStorageBindings(
    val serverDao: ServerDao,
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: AppConfigStore,
)
