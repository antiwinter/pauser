package com.opentune.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        MediaStateEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun mediaStateDao(): MediaStateDao
}
