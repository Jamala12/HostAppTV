package org.hostapptv.player

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hostapptv.player.core.EditionParser
import org.hostapptv.player.core.TextNormalizer
import org.hostapptv.player.data.db.AppDatabase
import org.hostapptv.player.data.db.ChannelEntity
import org.hostapptv.player.data.db.FavouriteEntity
import org.hostapptv.player.data.db.MovieEntity
import org.hostapptv.player.data.db.ProgressEntity
import org.hostapptv.player.data.repo.SearchQuery

/** Real SQLite on the device/emulator: grouping, generation-safe refresh, search ranking and account isolation. */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private lateinit var db: AppDatabase
    private val a = "acct-a"; private val b = "acct-b"

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    }
    @After fun close() = db.close()

    private fun movie(acct: String, id: Long, raw: String, tmdb: String?, gen: Long, added: Long = id): MovieEntity {
        val e = EditionParser.parse(raw)
        return MovieEntity(acct, id, raw, e.title, TextNormalizer.normalize(e.title), "1", null, null, added, "mp4", tmdb,
            EditionParser.groupKey("movie", tmdb, id), e.language, e.quality, e.year, generation = gen)
    }

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    @Test fun editionsGroupByTmdbOnly() = runBlocking {
        val dao = db.catalog()
        dao.insertMovieBatch(a, listOf(
            movie(a, 1, "EN - Heat (1995)", "949", 1), movie(a, 2, "FR - Heat (1995) [4K]", "949", 1),
            movie(a, 3, "EN - Heat (1995)", "", 1), movie(a, 4, "EN - Other", null, 1),
        ))
        val titles = dao.movies(a, null, "title").all()
        assertEquals(3, titles.size) // two Heat editions grouped; the one without TMDB id stays separate
        assertEquals(2, titles.first { it.groupKey == "movie:tmdb:949" }.editionCount)
        assertEquals(listOf(1L, 2L), dao.movieEditions(a, "movie:tmdb:949").map { it.streamId }.sorted())
    }

    @Test fun interruptedRefreshKeepsLibraryAndCompletedRefreshPrunes() = runBlocking {
        val dao = db.catalog()
        dao.insertMovieBatch(a, (1L..10L).map { movie(a, it, "EN - Film $it", "${100 + it}", gen = 1) })
        dao.finishMovies(a, 1)
        // Generation 2 delivers only part of the catalogue and is interrupted: nothing is pruned.
        dao.insertMovieBatch(a, (1L..3L).map { movie(a, it, "EN - Film $it", "${100 + it}", gen = 2) })
        assertEquals(10, dao.movies(a, null, "recent").all().size)
        // Generation 3 completes with 4 titles: stale rows go, and grouping is recomputed.
        dao.insertMovieBatch(a, (1L..4L).map { movie(a, it, "EN - Film $it", "${100 + it}", gen = 3) })
        dao.finishMovies(a, 3)
        assertEquals(4, dao.movies(a, null, "recent").all().size)
    }

    @Test fun regroupingAfterPrunePromotesSurvivingEdition() = runBlocking {
        val dao = db.catalog()
        dao.insertMovieBatch(a, listOf(movie(a, 1, "EN - X", "7", 1), movie(a, 2, "FR - X", "7", 1)))
        dao.finishMovies(a, 1)
        dao.insertMovieBatch(a, listOf(movie(a, 2, "FR - X", "7", 2))) // edition 1 removed by provider
        dao.finishMovies(a, 2)
        val t = dao.movies(a, null, "recent").all().single()
        assertEquals(2L, t.streamId); assertEquals(1, t.editionCount); assertTrue(t.isPrimary)
    }

    @Test fun searchRanksExactThenPrefixThenWordThenContains() = runBlocking {
        val dao = db.catalog()
        dao.insertMovieBatch(a, listOf(
            movie(a, 1, "The Ring Master", "1", 1), movie(a, 2, "Ring", "2", 1),
            movie(a, 3, "Rings of Power", "3", 1), movie(a, 4, "Boring Day", "4", 1), movie(a, 5, "Café Ring", "5", 1),
        ))
        val q = SearchQuery.from("ring")!!
        val ids = dao.searchMovies(a, q.q, q.pattern, q.prefix, q.wordPrefix).all().map { it.streamId }
        assertEquals(2L, ids[0]); assertEquals(3L, ids[1])
        assertEquals(setOf(1L, 5L), ids.subList(2, 4).toSet())
        assertEquals(4L, ids.last())
        assertEquals(5, dao.countMovies(a, q.pattern))
        val accent = SearchQuery.from("CAFE")!!
        assertEquals(listOf(5L), dao.searchMovies(a, accent.q, accent.pattern, accent.prefix, accent.wordPrefix).all().map { it.streamId })
    }

    @Test fun accountsAreIsolated() = runBlocking {
        val dao = db.catalog(); val user = db.userState()
        dao.upsertChannels(listOf(ChannelEntity(a, 1, 1, 1, "News", "news", "1", null, null, false, 1)))
        dao.upsertChannels(listOf(ChannelEntity(b, 1, 1, 1, "Other news", "other news", "1", null, null, false, 1)))
        user.addFavourite(FavouriteEntity(a, "live", "1", "News", null, 1))
        assertEquals(listOf("News"), dao.favouriteChannels(a).first().map { it.name })
        assertTrue(dao.favouriteChannels(b).first().isEmpty())
        val q = SearchQuery.from("news")!!
        assertEquals(1, dao.countChannels(b, q.pattern))
    }

    @Test fun continueWatchingShowsLatestUnfinishedPerTitle() = runBlocking {
        val user = db.userState()
        fun p(id: Long, series: Long?, group: String?, at: Long, finished: Boolean = false) =
            ProgressEntity(a, if (series != null) "episode" else "movie", id, group, series, 1, id.toInt(), "T$id", null, null, "mp4", 1_000, 10_000, at, finished)
        user.saveProgress(p(11, 9, "series:id:9", 100)); user.saveProgress(p(12, 9, "series:id:9", 200))
        user.saveProgress(p(1, null, "movie:tmdb:1", 150)); user.saveProgress(p(2, null, "movie:tmdb:2", 300, finished = true))
        val rows = user.continueWatching(a, 10).first()
        assertEquals(listOf(12L, 1L), rows.map { it.contentId }) // one entry per series, finished movie excluded, newest first
    }
}
