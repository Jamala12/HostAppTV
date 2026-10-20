package org.hostapptv.player.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    // --- Categories ---
    @Upsert suspend fun upsertCategories(items: List<CategoryEntity>)
    @Query("DELETE FROM categories WHERE accountId = :a AND kind = :kind AND generation < :gen")
    suspend fun pruneCategories(a: String, kind: String, gen: Long)
    @Query("SELECT * FROM categories WHERE accountId = :a AND kind = :kind ORDER BY sortOrder")
    fun categories(a: String, kind: String): Flow<List<CategoryEntity>>

    // --- Channels ---
    @Upsert suspend fun upsertChannels(items: List<ChannelEntity>)
    @Query("DELETE FROM channels WHERE accountId = :a AND generation < :gen")
    suspend fun pruneChannels(a: String, gen: Long)
    @Query("SELECT * FROM channels WHERE accountId = :a AND (:cat IS NULL OR categoryId = :cat) ORDER BY sortNum, name")
    fun channels(a: String, cat: String?): PagingSource<Int, ChannelEntity>
    @Query("SELECT * FROM channels WHERE accountId = :a AND streamId = :id")
    suspend fun channel(a: String, id: Long): ChannelEntity?
    @Query("SELECT c.* FROM channels c JOIN favourites f ON f.accountId = c.accountId AND f.kind = 'live' AND f.itemKey = CAST(c.streamId AS TEXT) WHERE c.accountId = :a ORDER BY f.addedAt DESC")
    fun favouriteChannels(a: String): Flow<List<ChannelEntity>>
    @Query("SELECT c.* FROM channels c JOIN favourites f ON f.accountId = c.accountId AND f.kind = 'live' AND f.itemKey = CAST(c.streamId AS TEXT) WHERE c.accountId = :a ORDER BY f.addedAt DESC")
    fun favouriteChannelsPaged(a: String): PagingSource<Int, ChannelEntity>
    @Query("SELECT streamId FROM channels WHERE accountId = :a AND (:cat IS NULL OR categoryId = :cat) ORDER BY sortNum, name LIMIT 20000")
    suspend fun channelIdsInCategory(a: String, cat: String?): List<Long>
    @Query("SELECT c.streamId FROM channels c JOIN favourites f ON f.accountId = c.accountId AND f.kind = 'live' AND f.itemKey = CAST(c.streamId AS TEXT) WHERE c.accountId = :a ORDER BY f.addedAt DESC")
    suspend fun favouriteChannelIds(a: String): List<Long>
    suspend fun channelIdsFor(a: String, cat: String?, favouritesOnly: Boolean): List<Long> =
        if (favouritesOnly) favouriteChannelIds(a) else channelIdsInCategory(a, cat)
    @Query("SELECT COUNT(*) FROM channels WHERE accountId = :a AND (:cat IS NULL OR categoryId = :cat)")
    fun channelCount(a: String, cat: String?): Flow<Int>

    // --- Movies ---
    @Upsert suspend fun upsertMovies(items: List<MovieEntity>)
    @Query("""UPDATE movies SET
        isPrimary = (streamId = (SELECT m2.streamId FROM movies m2 WHERE m2.accountId = movies.accountId AND m2.groupKey = movies.groupKey ORDER BY m2.streamId LIMIT 1)),
        editionCount = (SELECT COUNT(*) FROM movies m3 WHERE m3.accountId = movies.accountId AND m3.groupKey = movies.groupKey)
        WHERE accountId = :a AND groupKey IN (:keys)""")
    suspend fun regroupMovies(a: String, keys: List<String>)
    @Query("SELECT DISTINCT groupKey FROM movies WHERE accountId = :a AND generation < :gen")
    suspend fun staleMovieGroups(a: String, gen: Long): List<String>
    @Query("DELETE FROM movies WHERE accountId = :a AND generation < :gen")
    suspend fun pruneMovies(a: String, gen: Long)
    @Query("""SELECT * FROM movies WHERE accountId = :a AND isPrimary = 1 AND (:cat IS NULL OR categoryId = :cat)
        ORDER BY CASE WHEN :sort = 'title' THEN title END COLLATE NOCASE ASC,
                 CASE WHEN :sort = 'rating' THEN rating END DESC,
                 added DESC, streamId DESC""")
    fun movies(a: String, cat: String?, sort: String): PagingSource<Int, MovieEntity>
    @Query("SELECT COUNT(*) FROM movies WHERE accountId = :a AND isPrimary = 1 AND (:cat IS NULL OR categoryId = :cat)")
    fun movieCount(a: String, cat: String?): Flow<Int>
    @Query("SELECT * FROM movies WHERE accountId = :a AND isPrimary = 1 ORDER BY added DESC, streamId DESC LIMIT :limit")
    fun recentMovies(a: String, limit: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movies WHERE accountId = :a AND groupKey = :groupKey ORDER BY language, quality, streamId LIMIT 100")
    suspend fun movieEditions(a: String, groupKey: String): List<MovieEntity>
    @Query("SELECT * FROM movies WHERE accountId = :a AND streamId = :id")
    suspend fun movie(a: String, id: Long): MovieEntity?
    @Query("SELECT * FROM movies WHERE accountId = :a AND isPrimary = 1 AND (groupKey IN (:keys) OR title COLLATE NOCASE IN (:titles)) LIMIT 600")
    fun discoveryMovies(a: String, keys: List<String>, titles: List<String>): Flow<List<MovieEntity>>

    // --- Series ---
    @Upsert suspend fun upsertSeries(items: List<SeriesEntity>)
    @Query("""UPDATE series SET
        isPrimary = (seriesId = (SELECT s2.seriesId FROM series s2 WHERE s2.accountId = series.accountId AND s2.groupKey = series.groupKey ORDER BY s2.seriesId LIMIT 1)),
        editionCount = (SELECT COUNT(*) FROM series s3 WHERE s3.accountId = series.accountId AND s3.groupKey = series.groupKey)
        WHERE accountId = :a AND groupKey IN (:keys)""")
    suspend fun regroupSeries(a: String, keys: List<String>)
    @Query("SELECT DISTINCT groupKey FROM series WHERE accountId = :a AND generation < :gen")
    suspend fun staleSeriesGroups(a: String, gen: Long): List<String>
    @Query("DELETE FROM series WHERE accountId = :a AND generation < :gen")
    suspend fun pruneSeries(a: String, gen: Long)
    @Query("""SELECT * FROM series WHERE accountId = :a AND isPrimary = 1 AND (:cat IS NULL OR categoryId = :cat)
        ORDER BY CASE WHEN :sort = 'title' THEN title END COLLATE NOCASE ASC,
                 CASE WHEN :sort = 'rating' THEN rating END DESC,
                 lastModified DESC, seriesId DESC""")
    fun series(a: String, cat: String?, sort: String): PagingSource<Int, SeriesEntity>
    @Query("SELECT COUNT(*) FROM series WHERE accountId = :a AND isPrimary = 1 AND (:cat IS NULL OR categoryId = :cat)")
    fun seriesCount(a: String, cat: String?): Flow<Int>
    @Query("SELECT * FROM series WHERE accountId = :a AND groupKey = :groupKey ORDER BY language, quality, seriesId LIMIT 100")
    suspend fun seriesEditions(a: String, groupKey: String): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE accountId = :a AND seriesId = :id")
    suspend fun seriesById(a: String, id: Long): SeriesEntity?
    @Query("SELECT * FROM series WHERE accountId = :a AND isPrimary = 1 AND (groupKey IN (:keys) OR title COLLATE NOCASE IN (:titles)) LIMIT 600")
    fun discoverySeries(a: String, keys: List<String>, titles: List<String>): Flow<List<SeriesEntity>>

    // --- Search (normalised keys; pattern built by SearchQuery) ---
    @Query("""SELECT * FROM channels WHERE accountId = :a AND searchKey LIKE :pattern ESCAPE '\'
        ORDER BY CASE WHEN searchKey = :q THEN 0 WHEN searchKey LIKE :prefix ESCAPE '\' THEN 1 WHEN searchKey LIKE :wordPrefix ESCAPE '\' THEN 2 ELSE 3 END,
        length(searchKey), sortNum""")
    fun searchChannels(a: String, q: String, pattern: String, prefix: String, wordPrefix: String): PagingSource<Int, ChannelEntity>
    @Query("""SELECT * FROM movies WHERE accountId = :a AND isPrimary = 1 AND searchKey LIKE :pattern ESCAPE '\'
        ORDER BY CASE WHEN searchKey = :q THEN 0 WHEN searchKey LIKE :prefix ESCAPE '\' THEN 1 WHEN searchKey LIKE :wordPrefix ESCAPE '\' THEN 2 ELSE 3 END,
        length(searchKey), added DESC""")
    fun searchMovies(a: String, q: String, pattern: String, prefix: String, wordPrefix: String): PagingSource<Int, MovieEntity>
    @Query("""SELECT * FROM series WHERE accountId = :a AND isPrimary = 1 AND searchKey LIKE :pattern ESCAPE '\'
        ORDER BY CASE WHEN searchKey = :q THEN 0 WHEN searchKey LIKE :prefix ESCAPE '\' THEN 1 WHEN searchKey LIKE :wordPrefix ESCAPE '\' THEN 2 ELSE 3 END,
        length(searchKey), lastModified DESC""")
    fun searchSeries(a: String, q: String, pattern: String, prefix: String, wordPrefix: String): PagingSource<Int, SeriesEntity>
    @Query("SELECT COUNT(*) FROM channels WHERE accountId = :a AND searchKey LIKE :pattern ESCAPE '\\'")
    suspend fun countChannels(a: String, pattern: String): Int
    @Query("SELECT COUNT(*) FROM movies WHERE accountId = :a AND isPrimary = 1 AND searchKey LIKE :pattern ESCAPE '\\'")
    suspend fun countMovies(a: String, pattern: String): Int
    @Query("SELECT COUNT(*) FROM series WHERE accountId = :a AND isPrimary = 1 AND searchKey LIKE :pattern ESCAPE '\\'")
    suspend fun countSeries(a: String, pattern: String): Int

    // --- Sync bookkeeping ---
    @Upsert suspend fun upsertSyncState(state: SyncStateEntity)
    @Query("SELECT * FROM sync_state WHERE accountId = :a")
    fun syncStates(a: String): Flow<List<SyncStateEntity>>
    @Query("SELECT * FROM sync_state WHERE accountId = :a")
    suspend fun syncStatesNow(a: String): List<SyncStateEntity>

    @Transaction
    suspend fun insertMovieBatch(a: String, items: List<MovieEntity>) {
        upsertMovies(items)
        regroupMovies(a, items.map { it.groupKey }.distinct())
    }

    @Transaction
    suspend fun insertSeriesBatch(a: String, items: List<SeriesEntity>) {
        upsertSeries(items)
        regroupSeries(a, items.map { it.groupKey }.distinct())
    }

    @Transaction
    suspend fun finishMovies(a: String, gen: Long) {
        val stale = staleMovieGroups(a, gen)
        pruneMovies(a, gen)
        stale.chunked(400).forEach { regroupMovies(a, it) }
    }

    @Transaction
    suspend fun finishSeries(a: String, gen: Long) {
        val stale = staleSeriesGroups(a, gen)
        pruneSeries(a, gen)
        stale.chunked(400).forEach { regroupSeries(a, it) }
    }

    @Query("DELETE FROM channels WHERE accountId = :a") suspend fun clearChannels(a: String)
    @Query("DELETE FROM movies WHERE accountId = :a") suspend fun clearMovies(a: String)
    @Query("DELETE FROM series WHERE accountId = :a") suspend fun clearSeries(a: String)
    @Query("DELETE FROM categories WHERE accountId = :a") suspend fun clearCategories(a: String)
    @Query("DELETE FROM sync_state WHERE accountId = :a") suspend fun clearSync(a: String)
}

@Dao
interface UserStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourite(f: FavouriteEntity)
    @Query("DELETE FROM favourites WHERE accountId = :a AND kind = :kind AND itemKey = :key")
    suspend fun removeFavourite(a: String, kind: String, key: String)
    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE accountId = :a AND kind = :kind AND itemKey = :key)")
    fun isFavourite(a: String, kind: String, key: String): Flow<Boolean>
    @Query("SELECT * FROM favourites WHERE accountId = :a ORDER BY addedAt DESC")
    fun favourites(a: String): Flow<List<FavouriteEntity>>
    @Query("SELECT itemKey FROM favourites WHERE accountId = :a AND kind = 'live'")
    fun favouriteChannelKeys(a: String): Flow<List<String>>

    @Upsert suspend fun saveProgress(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE accountId = :a AND kind = :kind AND contentId = :id")
    suspend fun progress(a: String, kind: String, id: Long): ProgressEntity?
    @Query("SELECT * FROM progress WHERE accountId = :a AND kind = :kind AND contentId = :id")
    fun progressFlow(a: String, kind: String, id: Long): Flow<ProgressEntity?>
    @Query("SELECT * FROM progress WHERE accountId = :a AND kind = 'episode' AND seriesId = :seriesId")
    fun seriesProgress(a: String, seriesId: Long): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE accountId = :a AND kind = 'movie' AND groupKey = :groupKey ORDER BY updatedAt DESC LIMIT 1")
    fun movieGroupProgress(a: String, groupKey: String): Flow<ProgressEntity?>
    /** Continue Watching: one entry per movie group / series, unfinished, newest first. */
    @Query("""SELECT * FROM progress p WHERE accountId = :a AND finished = 0 AND positionMs > 0
        AND updatedAt = (SELECT MAX(updatedAt) FROM progress p2 WHERE p2.accountId = p.accountId
            AND COALESCE(CAST(p2.seriesId AS TEXT), p2.groupKey) = COALESCE(CAST(p.seriesId AS TEXT), p.groupKey))
        ORDER BY updatedAt DESC LIMIT :limit""")
    fun continueWatching(a: String, limit: Int): Flow<List<ProgressEntity>>
    @Query("DELETE FROM progress WHERE accountId = :a AND kind = :kind AND contentId = :id")
    suspend fun clearProgress(a: String, kind: String, id: Long)
}
