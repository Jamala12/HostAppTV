package uk.ahmedhome.tv

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
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.discovery.DiscoveryException
import uk.ahmedhome.tv.data.discovery.DiscoveryRepository
import uk.ahmedhome.tv.data.discovery.DiscoveryTitle
import uk.ahmedhome.tv.data.discovery.TmdbClient
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryTest {
    private val today = LocalDate.of(2026, 10, 19)

    @Test fun malformedAdultAndUnreleasedResultsNeverBecomePlayableRecommendations() {
        val result = TmdbClient.parsePage("""{"results":[
            {"id":1,"title":"Released","release_date":"2026-10-01","original_language":"ko","poster_path":"/poster.jpg"},
            {"id":2,"title":"Future","release_date":"2027-01-01"},
            {"id":3,"title":"Adult","adult":true},
            {"id":-1,"title":"Invalid"}, {"id":4},
            {"id":5,"title":"Unsafe poster","poster_path":"https://attacker.test/image.jpg"}
        ]}""", "movie", today)
        assertEquals(listOf(1L, 5L), result.map { it.id })
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", result[0].poster)
        assertNull(result[1].poster)
        for (body in listOf("<html>error</html>", "{}", "[]")) {
            assertThrows(DiscoveryException::class.java) { TmdbClient.parsePage(body, "movie", today) }
        }
    }

    @Test fun metadataOnlyMatchesCorrectMediaIdentityOrUniqueExactTitleAndYear() {
        val title = DiscoveryTitle(949, "movie", "Heat", "Heat", "en", "1995-12-15", null, "")
        fun movie(id: Long, tmdb: String?, key: String, year: Int?) = MovieEntity("a", id, "Heat", "Heat", "heat", null,
            null, null, null, "mp4", tmdb, key, "EN", null, year, generation = 1)
        val correct = movie(1, "949", "movie:tmdb:949", 1995)
        assertEquals(1L, DiscoveryRepository.match(title, listOf(correct), emptyList())?.providerId)
        assertNull(DiscoveryRepository.match(title.copy(kind = "tv"), listOf(correct), emptyList()))
        assertNull(DiscoveryRepository.match(title, listOf(movie(2, "999", "movie:tmdb:999", 1995)), emptyList()))
        assertNull(DiscoveryRepository.match(title, listOf(movie(3, null, "movie:id:3", 2020)), emptyList()))
        assertEquals(3L, DiscoveryRepository.match(title, listOf(movie(3, null, "movie:id:3", 1995)), emptyList())?.providerId)
        assertNull(DiscoveryRepository.match(title, listOf(movie(3, null, "movie:id:3", 1995), movie(4, null, "movie:id:4", 1995)), emptyList()))
    }

    @Test fun bearerAuthenticationFeedsAndOfflineCacheStayBoundedAndSeparateFromProvider() = runBlocking {
        val server = MockWebServer(); val fail = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer synthetic-private-tmdb-token", request.getHeader("Authorization"))
                assertFalse(request.path!!.contains("synthetic-private-tmdb-token"))
                if (fail.get()) return MockResponse().setResponseCode(503)
                val tv = request.path!!.contains("/tv/") || request.path!!.contains("/discover/tv")
                val name = if (tv) "name" else "title"; val date = if (tv) "first_air_date" else "release_date"
                return MockResponse().setBody("""{"results":[{"id":1,"$name":"Synthetic title","$date":"2026-09-01","original_language":"ko"}]}""")
            }
        }
        server.start()
        val directory = Files.createTempDirectory("ahmedhome-discovery-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = DiscoveryRepository(OkHttpClient(), scope, directory, { "synthetic-private-tmdb-token" },
                { token -> TmdbClient(OkHttpClient(), token, server.url("/3/")) { today } })
            repo.refresh()
            val fresh = withTimeout(10_000) { repo.state.first { it.feeds.size == 6 && !it.loading } }
            assertTrue(fresh.feeds.first { it.id == "korean-trending" }.titles.isNotEmpty())
            assertTrue(fresh.feeds.first { it.id == "chinese-trending" }.titles.isEmpty())
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 512 * 1024)
            assertFalse(directory.listFiles()!!.single().readText().contains("synthetic-private-tmdb-token"))
            fail.set(true); repo.refresh(force = true)
            val stale = withTimeout(10_000) { repo.state.first { it.error != null && !it.loading } }
            assertEquals(fresh.feeds, stale.feeds)
            assertTrue(server.requestCount <= 16)
            // A new process can restore the public cache without any provider catalogue in memory.
            val restored = DiscoveryRepository(OkHttpClient(), scope, directory, { "synthetic-private-tmdb-token" })
            restored.refresh()
            assertEquals(6, withTimeout(5_000) { restored.state.first { it.feeds.size == 6 } }.feeds.size)
            restored.cancel()
        } finally { scope.cancel(); server.shutdown(); directory.deleteRecursively() }
    }
}
