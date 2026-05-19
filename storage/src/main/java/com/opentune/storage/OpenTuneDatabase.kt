package com.opentune.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EndpointEntity::class,
        EntryStateEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun endpointDao(): EndpointDao
    abstract fun entryStateDao(): EntryStateDao
}
