package com.opentune.storage

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [
        ServerEntity::class,
        MediaStateEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun mediaStateDao(): MediaStateDao

    companion object {
        fun create(dbFilePath: String): OpenTuneDatabase =
            Room.databaseBuilder<OpenTuneDatabase>(name = dbFilePath)
                .setDriver(BundledSQLiteDriver())
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
