package org.hostapptv.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        FavouriteEntity::class, ProgressEntity::class, SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun userState(): UserStateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_movies_accountId_groupKey")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_movies_accountId_groupKey_streamId ON movies(accountId, groupKey, streamId)")
                db.execSQL("DROP INDEX IF EXISTS index_series_accountId_groupKey")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_series_accountId_groupKey_seriesId ON series(accountId, groupKey, seriesId)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
