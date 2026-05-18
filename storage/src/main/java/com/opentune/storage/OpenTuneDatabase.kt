package com.opentune.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        MediaStateEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun mediaStateDao(): MediaStateDao
}
