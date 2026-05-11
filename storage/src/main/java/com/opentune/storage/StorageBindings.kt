package com.opentune.storage

import com.opentune.storage.thumb.ThumbnailDiskCache

data class OpenTuneStorageBindings(
    val serverDao: ServerDao,
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: AppConfigStore,
    val thumbnailDiskCache: ThumbnailDiskCache,
)
