package com.opentune.storage

import android.content.Context

data class OpenTuneStorageBindings(
    val serverDao: ServerDao,
    val mediaStateStore: UserMediaStateStore,
    val appConfigStore: DataStoreAppConfigStore,
) {
    companion object {
        fun create(database: OpenTuneDatabase, context: Context): OpenTuneStorageBindings =
            OpenTuneStorageBindings(
                serverDao = database.serverDao(),
                mediaStateStore = RoomMediaStateStore(database),
                appConfigStore = DataStoreAppConfigStore(context.applicationContext),
            )
    }
}
