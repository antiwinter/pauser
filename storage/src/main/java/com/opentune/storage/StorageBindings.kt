package com.opentune.storage

import android.content.Context
import com.opentune.storage.thumb.ThumbnailDiskCache
import java.io.File

data class OpenTuneStorageBindings(
    val serverDao: ServerDao,
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: DataStoreAppConfigStore,
    val thumbnailDiskCache: ThumbnailDiskCache,
) {
    companion object {
        fun create(database: OpenTuneDatabase, context: Context): OpenTuneStorageBindings {
            val cacheDir = File(context.applicationContext.cacheDir, "covers")
            return OpenTuneStorageBindings(
                serverDao = database.serverDao(),
                mediaStateStore = RoomMediaStateStore(database),
                appConfigStore = DataStoreAppConfigStore(context.applicationContext),
                thumbnailDiskCache = ThumbnailDiskCache(cacheDir),
            )
        }
    }
}
