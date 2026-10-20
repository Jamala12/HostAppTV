package uk.ahmedhome.tv.data.discovery

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import uk.ahmedhome.tv.data.db.CatalogDao
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.db.SeriesEntity
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DiscoveryFeed(val id: String, val label: String, val titles: List<DiscoveryTitle>, val updatedAt: Long)
data class DiscoveryState(val configured: Boolean = false, val loading: Boolean = false, val feeds: List<DiscoveryFeed> = emptyList(), val error: String? = null)
data class AvailableTitle(val metadata: DiscoveryTitle, val providerId: Long, val groupKey: String, val artwork: String?)
data class AvailableFeed(val feed: DiscoveryFeed, val titles: List<AvailableTitle>)
data class HomeDiscovery(val source: DiscoveryState = DiscoveryState(), val feeds: List<AvailableFeed> = emptyList())

/** Small public-metadata cache; provider credentials, stream URLs and the TMDB token never enter it. */
class DiscoveryRepository(
    private val http: OkHttpClient, private val scope: CoroutineScope,
    private val directory: File, private val readToken: () -> String?,
    private val makeClient: (String) -> TmdbClient = { TmdbClient(http, it) },
) {
    private val mutableState = MutableStateFlow(DiscoveryState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var lastAttempt = 0L
    private var tokenKey: String? = null
    @Volatile private var revision = 0L
    private val adapter = Moshi.Builder().build().adapter(Map::class.java)

    fun cancel() {
        val interrupted = job?.isActive == true
        revision++; job?.cancel(); job = null
        if (interrupted) lastAttempt = 0L
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun publish(generation: Long, value: DiscoveryState) { if (revision == generation) mutableState.value = value }

    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) return
        val generation = ++revision
        job = scope.launch(Dispatchers.IO) {
            try {
                val token = readToken()?.takeIf { it.isNotBlank() }
                coroutineContext.ensureActive()
                if (token == null) { publish(generation, DiscoveryState()); return@launch }
                val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
                if (tokenKey != key) {
                    tokenKey = key; lastAttempt = 0L
                    publish(generation, DiscoveryState(configured = true, feeds = readCache(key)))
                }
                val now = System.currentTimeMillis()
                if (!force && (now - lastAttempt < RETRY_INTERVAL || (mutableState.value.feeds.size == 6 && mutableState.value.feeds.all { now - it.updatedAt < REFRESH_INTERVAL }))) return@launch
                lastAttempt = now
                publish(generation, mutableState.value.copy(configured = true, loading = true, error = null))
                val client = makeClient(token)
                val feeds = mutableState.value.feeds.associateBy { it.id }.toMutableMap()
                var failure: String? = null
                suspend fun load(block: suspend () -> List<DiscoveryTitle>): List<DiscoveryTitle>? = try { block() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = (e as? DiscoveryException)?.reason ?: "Discovery couldn't refresh. Your saved library is still available."; null }
                fun save(id: String, titles: List<DiscoveryTitle>) { feeds[id] = DiscoveryFeed(id, LABELS.getValue(id), titles, now) }
                withTimeout(90_000) {
                    val movies = load { client.trending("movie") }
                    val series = load { client.trending("tv") }
                    movies?.let { save("trending-movies", it) }
                    series?.let { save("trending-series", it) }
                    if (movies != null && series != null) {
                        val combined = (movies.mapIndexed { i, t -> i to t } + series.mapIndexed { i, t -> i to t }).sortedBy { it.first }.map { it.second }
                        save("korean-trending", combined.filter { it.language == "ko" })
                        save("chinese-trending", combined.filter { it.language in setOf("zh", "cn") })
                    }
                    publish(generation, DiscoveryState(true, true, ordered(feeds), failure))
                    load { client.newTitles(listOf("ko")) }?.let { save("korean-new", it) }
                    load { client.newTitles(listOf("zh", "cn")) }?.let { save("chinese-new", it) }
                }
                val result = ordered(feeds)
                writeCache(key, result)
                publish(generation, DiscoveryState(true, false, result, failure))
            } catch (_: TimeoutCancellationException) {
                publish(generation, mutableState.value.copy(error = "Discovery refresh timed out. Saved titles are still available."))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { publish(generation, mutableState.value.copy(error = "Discovery couldn't refresh. Your saved library is still available.")) }
            finally { if (revision == generation) mutableState.value = mutableState.value.copy(loading = false) }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun available(accountId: String, dao: CatalogDao): Flow<HomeDiscovery> = state.flatMapLatest { source ->
        val titles = source.feeds.flatMap { it.titles }.distinctBy { it.key }
        if (titles.isEmpty()) return@flatMapLatest flowOf(HomeDiscovery(source, source.feeds.map { AvailableFeed(it, emptyList()) }))
        val movies = titles.filter { it.kind == "movie" }; val series = titles.filter { it.kind == "tv" }
        combine(
            dao.discoveryMovies(accountId, movies.map { "movie:tmdb:${it.id}" }, movies.flatMap { listOf(it.title, it.originalTitle) }.distinct().take(400)),
            dao.discoverySeries(accountId, series.map { "series:tmdb:${it.id}" }, series.flatMap { listOf(it.title, it.originalTitle) }.distinct().take(400)),
        ) { movieRows, seriesRows ->
            HomeDiscovery(source, source.feeds.map { feed -> AvailableFeed(feed, feed.titles.mapNotNull { match(it, movieRows, seriesRows) }.distinctBy { it.groupKey }.take(24)) })
        }
    }.flowOn(Dispatchers.Default)

    private fun ordered(feeds: Map<String, DiscoveryFeed>) = LABELS.keys.mapNotNull(feeds::get)

    private fun readCache(key: String): List<DiscoveryFeed> = runCatching {
        val file = File(directory, "$key.json")
        if (file.length() !in 1..MAX_CACHE_BYTES) return emptyList()
        val root = adapter.fromJson(file.readText()) ?: return emptyList()
        if ((root["version"] as? Number)?.toInt() != 1) return emptyList()
        val now = System.currentTimeMillis()
        (root["feeds"] as? List<*>).orEmpty().take(6).mapNotNull { value ->
            val feed = value as? Map<*, *> ?: return@mapNotNull null
            val id = feed["id"] as? String ?: return@mapNotNull null
            val label = LABELS[id] ?: return@mapNotNull null
            val at = (feed["updatedAt"] as? Number)?.toLong() ?: return@mapNotNull null
            if (now - at !in 0..MAX_STALE_AGE) return@mapNotNull null
            val titles = (feed["titles"] as? List<*>).orEmpty().take(200).mapNotNull title@{ entry ->
                val t = entry as? Map<*, *> ?: return@title null
                val kind = t["kind"] as? String ?: return@title null
                if (kind !in setOf("movie", "tv")) return@title null
                val tid = (t["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return@title null
                val title = t["title"] as? String ?: return@title null
                DiscoveryTitle(tid, kind, title.take(200), (t["originalTitle"] as? String).orEmpty().take(200),
                    (t["language"] as? String).orEmpty(), t["releaseDate"] as? String,
                    (t["poster"] as? String)?.takeIf { it.startsWith("https://image.tmdb.org/t/p/w342/") }, (t["overview"] as? String).orEmpty().take(300))
            }
            DiscoveryFeed(id, label, titles, at)
        }
    }.getOrDefault(emptyList())

    private fun writeCache(key: String, feeds: List<DiscoveryFeed>) {
        val data = adapter.toJson(mapOf("version" to 1, "feeds" to feeds.map { f -> mapOf("id" to f.id, "updatedAt" to f.updatedAt,
            "titles" to f.titles.map { t -> mapOf("id" to t.id, "kind" to t.kind, "title" to t.title, "originalTitle" to t.originalTitle,
                "language" to t.language, "releaseDate" to t.releaseDate, "poster" to t.poster, "overview" to t.overview) }) }))
        if (data.toByteArray().size > MAX_CACHE_BYTES) return
        directory.mkdirs()
        directory.listFiles()?.filter { it.name != "$key.json" }?.forEach { it.delete() }
        val temporary = File(directory, "$key.tmp")
        try { temporary.writeText(data); check(temporary.renameTo(File(directory, "$key.json"))) }
        finally { temporary.delete() }
    }

    companion object {
        const val REFRESH_INTERVAL = 6 * 3600_000L
        private const val RETRY_INTERVAL = 10 * 60_000L
        private const val MAX_STALE_AGE = 7 * 24 * 3600_000L
        private const val MAX_CACHE_BYTES = 512L * 1024
        val LABELS = linkedMapOf("trending-movies" to "Trending films", "trending-series" to "Trending series",
            "korean-trending" to "Trending in Korean", "korean-new" to "New in Korean",
            "chinese-trending" to "Trending in Chinese", "chinese-new" to "New in Chinese")

        internal fun match(t: DiscoveryTitle, movies: List<MovieEntity>, series: List<SeriesEntity>): AvailableTitle? {
            val candidates = if (t.kind == "movie") movies.map { Candidate(it.streamId, it.groupKey, it.title, it.year, it.tmdbId, it.poster) }
                else series.map { Candidate(it.seriesId, it.groupKey, it.title, it.year, it.tmdbId, it.cover) }
            candidates.firstOrNull { it.key == "${if (t.kind == "movie") "movie" else "series"}:tmdb:${t.id}" }
                ?.let { return AvailableTitle(t, it.id, it.key, t.poster ?: it.artwork) }
            // A title fallback never overrides a conflicting TMDB ID, guesses a year, or merges editions.
            val exact = candidates.filter { c -> c.tmdb?.toLongOrNull()?.let { it <= 0 } != false && t.year != null && c.year == t.year &&
                (c.title.equals(t.title, true) || c.title.equals(t.originalTitle, true)) }.distinctBy { it.key }
            return exact.singleOrNull()?.let { AvailableTitle(t, it.id, it.key, t.poster ?: it.artwork) }
        }
        private data class Candidate(val id: Long, val key: String, val title: String, val year: Int?, val tmdb: String?, val artwork: String?)
    }
}
