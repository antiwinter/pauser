package com.opentune.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        FavoriteEntity::class,
        PlaybackProgressEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        fun create(context: Context): OpenTuneDatabase =
            Room.databaseBuilder(context, OpenTuneDatabase::class.java, "opentune.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
