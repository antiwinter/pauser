package com.opentune.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EndpointEntity::class,
        EntryStateEntity::class,
        ProxyConfigEntity::class,
        ProxyAssignmentEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun endpointDao(): EndpointDao
    abstract fun entryStateDao(): EntryStateDao
    abstract fun proxyConfigDao(): ProxyConfigDao
    abstract fun proxyAssignmentDao(): ProxyAssignmentDao
}
