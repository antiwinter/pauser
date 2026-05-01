package com.opentune.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmbyServerEntity::class,
        FavoriteEntity::class,
        PlaybackProgressEntity::class,
        SmbSourceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class OpenTuneDatabase : RoomDatabase() {
    abstract fun embyServerDao(): EmbyServerDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun smbSourceDao(): SmbSourceDao

    companion object {
        fun create(context: Context): OpenTuneDatabase =
            Room.databaseBuilder(context, OpenTuneDatabase::class.java, "opentune.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
