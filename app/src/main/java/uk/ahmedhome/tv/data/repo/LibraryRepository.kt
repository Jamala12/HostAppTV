package uk.ahmedhome.tv.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import uk.ahmedhome.tv.data.db.AppDatabase
import uk.ahmedhome.tv.data.db.ChannelEntity
import uk.ahmedhome.tv.data.db.FavouriteEntity
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.db.ProgressEntity
import uk.ahmedhome.tv.data.db.SeriesEntity

enum class SortOrder(val key: String, val label: String) { RECENT("recent", "Recently added"), TITLE("title", "A–Z"), RATING("rating", "Rating") }

class LibraryRepository(db: AppDatabase) {
    val catalog = db.catalog()
    val user = db.userState()

    private fun config(pageSize: Int) = PagingConfig(pageSize = pageSize, prefetchDistance = pageSize, initialLoadSize = pageSize * 2, maxSize = pageSize * 8, enablePlaceholders = false)

    private fun <T : Any> pager(pageSize: Int, source: () -> PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(config(pageSize), pagingSourceFactory = source).flow

    fun channels(a: String, category: String?, favouritesOnly: Boolean): Flow<PagingData<ChannelEntity>> =
        pager(60) { if (favouritesOnly) catalog.favouriteChannelsPaged(a) else catalog.channels(a, category) }
    fun movies(a: String, category: String?, sort: SortOrder): Flow<PagingData<MovieEntity>> = pager(40) { catalog.movies(a, category, sort.key) }
    fun series(a: String, category: String?, sort: SortOrder): Flow<PagingData<SeriesEntity>> = pager(40) { catalog.series(a, category, sort.key) }

    fun searchChannels(a: String, q: SearchQuery) = pager(40) { catalog.searchChannels(a, q.q, q.pattern, q.prefix, q.wordPrefix) }
    fun searchMovies(a: String, q: SearchQuery) = pager(40) { catalog.searchMovies(a, q.q, q.pattern, q.prefix, q.wordPrefix) }
    fun searchSeries(a: String, q: SearchQuery) = pager(40) { catalog.searchSeries(a, q.q, q.pattern, q.prefix, q.wordPrefix) }

    suspend fun toggleFavourite(a: String, kind: String, key: String, title: String, image: String?, currentlyFavourite: Boolean) {
        if (currentlyFavourite) user.removeFavourite(a, kind, key)
        else user.addFavourite(FavouriteEntity(a, kind, key, title, image, System.currentTimeMillis()))
    }

    suspend fun saveProgress(p: ProgressEntity) = user.saveProgress(p)
}
