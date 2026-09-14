package com.example.itellytv.data.source

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.itellytv.data.model.ChannelEntity
import com.example.itellytv.data.model.PlaylistEntity
import com.example.itellytv.data.model.RecentPlayEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        RecentPlayEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class iTellyDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun recentPlayDao(): RecentPlayDao

    companion object {
        @Volatile
        private var INSTANCE: iTellyDatabase? = null

        fun get(context: Context): iTellyDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                iTellyDatabase::class.java,
                "itellytv.db"
            )
                // Migrations will land with the schema version bump.
                // For M2 we use fallbackToDestructiveMigration since
                // there's no prior user data to preserve.
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
