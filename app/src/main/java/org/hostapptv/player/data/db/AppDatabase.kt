package org.hostapptv.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CategoryEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        FavouriteEntity::class, ProgressEntity::class, SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun userState(): UserStateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
