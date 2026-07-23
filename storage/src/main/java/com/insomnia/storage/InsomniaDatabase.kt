package com.insomnia.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EndpointEntity::class,
        EntryStateEntity::class,
        ProxyEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class InsomniaDatabase : RoomDatabase() {
    abstract fun endpointDao(): EndpointDao
    abstract fun entryStateDao(): EntryStateDao
    abstract fun proxyDao(): ProxyDao
}
