package uk.ahmedhome.tv.data.discovery

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import uk.ahmedhome.tv.data.xtream.await
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class DiscoveryTitle(
    val id: Long, val kind: String, val title: String, val originalTitle: String,
    val language: String, val releaseDate: String?, val poster: String?, val overview: String,
) {
    val key: String get() = "$kind:$id"
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

class DiscoveryException(val reason: String) : IOException(reason)

/** No runtime endpoint override or embedded API key. Tests inject their own HTTP endpoint. */
class TmdbClient(
    http: OkHttpClient,
    private val token: String,
    private val base: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun validateToken() { page("trending/movie/week", "movie", mapOf("page" to "1")) }

    suspend fun trending(kind: String): List<DiscoveryTitle> {
        val titles = mutableListOf<DiscoveryTitle>()
        for (page in 1..5) {
            val result = page("trending/$kind/week", kind, mapOf("page" to page.toString()))
            titles += result
            if (result.size < 20) break
        }
        return titles.distinctBy { it.key }.take(100)
    }

    suspend fun newTitles(languages: List<String>): List<DiscoveryTitle> {
        val titles = mutableListOf<DiscoveryTitle>()
        for (language in languages) for (kind in listOf("movie", "tv")) {
            val dateField = if (kind == "movie") "primary_release_date" else "first_air_date"
            titles += page("discover/$kind", kind, mapOf(
                "with_original_language" to language, "sort_by" to "$dateField.desc",
                "$dateField.lte" to today().toString(), "$dateField.gte" to today().minusYears(1).toString(),
                "vote_count.gte" to "5", "include_adult" to "false", "include_video" to "false",
            ))
        }
        return titles.distinctBy { it.key }.sortedByDescending { it.releaseDate }.take(60)
    }

    private suspend fun page(path: String, kind: String, query: Map<String, String>): List<DiscoveryTitle> = withContext(Dispatchers.IO) {
        require(kind == "movie" || kind == "tv")
        val url = base.newBuilder().addPathSegments(path).addQueryParameter("language", "en-GB")
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val call = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").build())
        // Cancellation closes the response even while parsing a body, not just while awaiting headers.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.await().use { response ->
                if (!response.isSuccessful) throw DiscoveryException(when (response.code) {
                    401, 403 -> "TMDB did not accept the discovery token. Update it in Settings."
                    429 -> "TMDB is busy. Saved discovery stays available; try again later."
                    else -> "Discovery is temporarily unavailable. Your provider library is still available."
                })
                val buffer = Buffer()
                val source = response.body.source()
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer, 8192)
                    if (read == -1L) break
                    if (buffer.size > MAX_RESPONSE_BYTES) throw DiscoveryException("The discovery response was too large.")
                }
                parsePage(buffer.readUtf8(), kind, today())
            }
        } catch (e: DiscoveryException) { throw e }
        catch (e: IOException) {
            coroutineContext.ensureActive()
            throw DiscoveryException("Couldn't refresh discovery. Check the connection; your library is still available.")
        } finally { cancellation.cancel() }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024

        internal fun parsePage(json: String, kind: String, today: LocalDate): List<DiscoveryTitle> {
            if (json.toByteArray().size > MAX_RESPONSE_BYTES) throw DiscoveryException("The discovery response was too large.")
            val root = try { JsonReader.of(Buffer().writeUtf8(json)).readJsonValue() as? Map<*, *> }
                catch (_: Exception) { null }
                ?: throw DiscoveryException("TMDB returned an unreadable discovery feed.")
            val results = root["results"] as? List<*> ?: throw DiscoveryException("TMDB returned an unreadable discovery feed.")
            return results.take(20).mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
                if (m["adult"] == true) return@mapNotNull null
                val title = (m[if (kind == "movie") "title" else "name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: return@mapNotNull null
                val date = (m[if (kind == "movie") "release_date" else "first_air_date"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (date != null && date > today) return@mapNotNull null
                val poster = (m["poster_path"] as? String)?.takeIf { Regex("/[A-Za-z0-9._-]+\\.(jpg|png)").matches(it) }
                    ?.let { "https://image.tmdb.org/t/p/w342$it" }
                DiscoveryTitle(id, kind, title, (m[if (kind == "movie") "original_title" else "original_name"] as? String)?.take(200) ?: title,
                    (m["original_language"] as? String).orEmpty(), date?.toString(), poster, (m["overview"] as? String).orEmpty().take(300))
            }.distinctBy { it.key }
        }
    }
}
