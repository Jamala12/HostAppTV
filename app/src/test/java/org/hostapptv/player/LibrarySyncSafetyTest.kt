package org.hostapptv.player

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.hostapptv.player.data.db.CatalogDao
import org.hostapptv.player.data.db.ChannelEntity
import org.hostapptv.player.data.db.MovieEntity
import org.hostapptv.player.data.db.SeriesEntity
import org.hostapptv.player.data.db.SyncStateEntity
import org.hostapptv.player.data.sync.LibrarySync
import org.hostapptv.player.data.sync.cataloguesNeedingRefresh
import org.hostapptv.player.data.xtream.CatalogKind
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.data.xtream.XtreamClient
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class LibrarySyncSafetyTest {
    @Test fun failedAndInterruptedCataloguesRetryWithoutRedownloadingFreshSuccessfulOnes() {
        val now = 50_000_000L
        fun state(kind: String, status: String, at: Long = now) = SyncStateEntity("a", kind, at, now, 0, status, null)
        assertEquals(listOf(CatalogKind.MOVIE, CatalogKind.SERIES), cataloguesNeedingRefresh(listOf(
            state("live", "complete"), state("movie", "failed"), state("series", "interrupted"),
        ), now))
        assertTrue(cataloguesNeedingRefresh(listOf("live", "movie", "series").map { state(it, "complete") }, now).isEmpty())
        assertEquals(CatalogKind.entries, cataloguesNeedingRefresh(emptyList(), now))
        assertEquals(CatalogKind.entries, cataloguesNeedingRefresh(listOf("live", "movie", "series").map { state(it, "complete", 1) }, now))
    }

    private class DatabaseFake(var failWrites: Boolean = false) {
        val states = ConcurrentHashMap<String, SyncStateEntity>()
        val channels = ConcurrentHashMap<Long, ChannelEntity>()
        val movies = ConcurrentHashMap<Long, MovieEntity>()
        val series = ConcurrentHashMap<Long, SeriesEntity>()
        @Suppress("UNCHECKED_CAST")
        val dao = Proxy.newProxyInstance(CatalogDao::class.java.classLoader, arrayOf(CatalogDao::class.java)) { _, method, args ->
            when (method.name) {
                "syncStatesNow" -> states.values.toList()
                "upsertSyncState" -> { if (failWrites) error("synthetic storage failure"); val s = args!![0] as SyncStateEntity; states[s.kind] = s; Unit }
                "upsertCategories", "pruneCategories" -> Unit
                "upsertChannels" -> { (args!![0] as List<ChannelEntity>).forEach { channels[it.streamId] = it }; Unit }
                "pruneChannels" -> { val gen = args!![1] as Long; channels.entries.removeIf { it.value.generation < gen }; Unit }
                "insertMovieBatch" -> { (args!![1] as List<MovieEntity>).forEach { movies[it.streamId] = it }; Unit }
                "insertSeriesBatch" -> { (args!![1] as List<SeriesEntity>).forEach { series[it.seriesId] = it }; Unit }
                "finishMovies", "finishSeries" -> Unit
                else -> error("Unexpected DAO operation: ${method.name}")
            }
        } as CatalogDao
    }

    @Test fun emptyRefreshKeepsPreviousLibraryAndTheGuardSurvivesRepeatedFailures() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]") }
        server.start()
        val db = DatabaseFake()
        db.channels[1] = ChannelEntity("a", 1, 1, 1, "Football", "football", null, null, null, false, 1)
        db.states["live"] = SyncStateEntity("a", "live", 1, 1, 1, "complete", null)
        val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors += e })
        try {
            val sync = LibrarySync(db.dao, scope)
            val client = XtreamClient(OkHttpClient(), ProviderConfig(server.url("/").toString(), "demo", "demo", true))
            repeat(2) {
                sync.refresh("a", client, listOf(CatalogKind.LIVE))
                withTimeout(5_000) { sync.progress.first { it.error != null && !it.running } }
                assertEquals(1, db.channels.size); assertEquals(1, db.states["live"]!!.count)
                assertEquals(1L, db.states["live"]!!.lastCompleteAt)
            }
            assertTrue(errors.isEmpty())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun storageFailureIsAnActionableRefreshErrorInsteadOfAnUncaughtCrash() = runBlocking {
        val db = DatabaseFake(failWrites = true); val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors += e })
        try {
            val sync = LibrarySync(db.dao, scope)
            sync.refresh("a", XtreamClient(OkHttpClient(), ProviderConfig("https://example.invalid", "demo", "demo", false)), listOf(CatalogKind.LIVE))
            val result = withTimeout(5_000) { sync.progress.first { it.error != null && !it.running } }
            assertTrue(result.error!!.contains("storage")); assertTrue(errors.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun leavingBrowsingCancelsBulkTransferAndReturningSafelyRestartsIt() = runBlocking {
        val body = (1..1000).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Synthetic football channel $it"}""" }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.contains("get_live_categories")) MockResponse().setBody("[]")
                else MockResponse().setBody(body).throttleBody(2048, 20, TimeUnit.MILLISECONDS)
        }
        server.start()
        val db = DatabaseFake(); val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors += e })
        try {
            val sync = LibrarySync(db.dao, scope)
            sync.setForeground(false)
            sync.refresh("a", XtreamClient(OkHttpClient(), ProviderConfig(server.url("/").toString(), "demo", "demo", true)), listOf(CatalogKind.LIVE))
            withTimeout(5_000) { sync.progress.first { it.paused } }
            assertEquals(0, server.requestCount)
            sync.setForeground(true)
            withTimeout(5_000) { sync.progress.first { it.itemsInCurrent >= 500 } }
            sync.setForeground(false)
            withTimeout(2_000) { sync.progress.first { it.paused && !it.running } }
            assertTrue(db.channels.isNotEmpty())
            sync.setForeground(true)
            withTimeout(10_000) { sync.progress.first { !it.running && !it.paused } }
            assertEquals(1000, db.channels.size)
            assertEquals("complete", db.states["live"]!!.status)
            assertTrue(errors.isEmpty())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun returningFromPlaybackDoesNotDownloadCompletedCataloguesAgain() = runBlocking {
        val movieBody = (1..1000).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Film $it"}""" }
        val actions = CopyOnWriteArrayList<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.requestUrl!!.queryParameter("action")!!
                    actions += action
                    return when (action) {
                        "get_live_streams" -> MockResponse().setBody("""[{"stream_id":1,"name":"News"}]""")
                        "get_vod_streams" -> MockResponse().setBody(movieBody).throttleBody(1024, 20, TimeUnit.MILLISECONDS)
                        "get_series" -> MockResponse().setBody("""[{"series_id":1,"name":"Serial"}]""")
                        else -> MockResponse().setBody("[]")
                    }
                }
            }
            start()
        }
        val db = DatabaseFake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val sync = LibrarySync(db.dao, scope)
            sync.refresh("a", XtreamClient(OkHttpClient(), ProviderConfig(server.url("/").toString(), "demo", "demo", true)))
            withTimeout(5_000) { sync.progress.first { it.current == CatalogKind.MOVIE && it.itemsInCurrent >= 500 } }
            sync.setForeground(false)
            withTimeout(2_000) { sync.progress.first { it.paused && !it.running } }
            sync.setForeground(true)
            withTimeout(10_000) { sync.progress.first { !it.running && !it.paused } }
            assertEquals(1, actions.count { it == "get_live_streams" })
            assertEquals(2, actions.count { it == "get_vod_streams" })
            assertEquals(1, actions.count { it == "get_series" })
            assertEquals(1000, db.states["movie"]!!.count)
            assertEquals("complete", db.states["series"]!!.status)
        } finally { scope.cancel(); server.shutdown() }
    }
}
