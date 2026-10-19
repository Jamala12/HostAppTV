package uk.ahmedhome.tv.data.xtream

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Direct client for the Xtream Codes `player_api.php` contract. Large catalogue arrays are
 * parsed as a stream and handed out in bounded batches; no full response is held in memory.
 */
class XtreamClient(private val http: OkHttpClient, val config: ProviderConfig) {

    private val base: HttpUrl = normalizeBaseUrl(config.baseUrl)
        ?: throw IllegalArgumentException("Invalid server address")

    private fun api(action: String? = null, vararg params: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()

    suspend fun authenticate(): AccountInfo = request(api()) { r ->
        var info: AccountInfo? = null
        var authSeen = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "user_info" -> {
                    var formats = emptyList<String>()
                    val m = r.readFlatObject { name, rr ->
                        if (name == "allowed_output_formats" && rr.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            val list = mutableListOf<String>()
                            rr.beginArray(); while (rr.hasNext()) rr.readScalar()?.let(list::add); rr.endArray()
                            formats = list; true
                        } else false
                    }
                    authSeen = true
                    if (!m["auth"].asBool()) throw XtreamException.Unauthorized()
                    val status = m["status"].cleanText()
                    info = AccountInfo(
                        active = status == null || status.equals("Active", ignoreCase = true),
                        status = status,
                        expiresAtEpochSec = m["exp_date"].asLong(),
                        maxConnections = m["max_connections"].asInt(),
                        activeConnections = m["active_cons"].asInt(),
                        outputFormats = formats,
                    )
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (!authSeen) throw XtreamException.Unauthorized()
        info!!
    }

    suspend fun categories(kind: CatalogKind): List<XCategory> = request(api(kind.categoriesAction)) { r ->
        val out = ArrayList<XCategory>()
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); return@request out }
        r.beginArray()
        var order = 0
        while (r.hasNext()) {
            val m = r.readFlatObject()
            val id = m["category_id"].cleanText() ?: continue
            out += XCategory(id, m["category_name"].cleanText() ?: "Untitled", order++)
        }
        r.endArray()
        out
    }

    suspend fun liveStreams(batchSize: Int = 500, onBatch: suspend (List<XLiveStream>) -> Unit): Int =
        streamArray(api("get_live_streams"), batchSize, onBatch) { m ->
            val id = m["stream_id"].asLong() ?: return@streamArray null
            XLiveStream(
                streamId = id, num = m["num"].asInt(), name = m["name"].cleanText() ?: "Channel $id",
                icon = m["stream_icon"].cleanText(), epgChannelId = m["epg_channel_id"].cleanText(),
                categoryId = m["category_id"].cleanText(), tvArchive = m["tv_archive"].asBool(), added = m["added"].asLong(),
            )
        }

    suspend fun movies(batchSize: Int = 500, onBatch: suspend (List<XMovie>) -> Unit): Int =
        streamArray(api("get_vod_streams"), batchSize, onBatch) { m ->
            val id = m["stream_id"].asLong() ?: return@streamArray null
            XMovie(
                streamId = id, name = m["name"].cleanText() ?: "Untitled", icon = m["stream_icon"].cleanText(),
                categoryId = m["category_id"].cleanText(), rating = m["rating"].asDouble(), added = m["added"].asLong(),
                extension = m["container_extension"].cleanText(), tmdbId = (m["tmdb"] ?: m["tmdb_id"]).cleanText(),
                year = m["year"].cleanText(),
            )
        }

    suspend fun series(batchSize: Int = 500, onBatch: suspend (List<XSeries>) -> Unit): Int =
        streamArray(api("get_series"), batchSize, onBatch) { m ->
            val id = m["series_id"].asLong() ?: return@streamArray null
            XSeries(
                seriesId = id, name = m["name"].cleanText() ?: "Untitled", cover = m["cover"].cleanText(),
                plot = m["plot"].cleanText(), categoryId = m["category_id"].cleanText(), rating = m["rating"].asDouble(),
                lastModified = m["last_modified"].asLong(), tmdbId = (m["tmdb"] ?: m["tmdb_id"]).cleanText(),
                year = (m["year"] ?: m["releaseDate"] ?: m["release_date"]).cleanText(), genre = m["genre"].cleanText(),
            )
        }

    suspend fun seriesInfo(seriesId: Long): XSeriesInfo = request(api("get_series_info", "series_id" to seriesId.toString())) { r ->
        var plot: String? = null; var genre: String? = null; var release: String? = null
        val seasons = sortedSetOf<Int>()
        val episodes = ArrayList<XEpisode>()
        fun readEpisode(rr: JsonReader, seasonHint: Int?) {
            var dur: Long? = null; var eplot: String? = null; var image: String? = null
            val m = rr.readFlatObject { name, nr ->
                if (name == "info" && nr.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    val info = nr.readFlatObject()
                    dur = info["duration_secs"].asLong() ?: parseDuration(info["duration"])
                    eplot = info["plot"].cleanText(); image = info["movie_image"].cleanText()
                    true
                } else false
            }
            val id = m["id"].asLong() ?: return
            val season = m["season"].asInt() ?: seasonHint ?: 1
            seasons += season
            episodes += XEpisode(id, season, m["episode_num"].asInt() ?: 0, m["title"].cleanText() ?: "Episode",
                m["container_extension"].cleanText(), dur, eplot, image)
        }
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); return@request XSeriesInfo(null, null, null, emptyList(), emptyList()) }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "info" -> if (r.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    val m = r.readFlatObject()
                    plot = m["plot"].cleanText(); genre = m["genre"].cleanText(); release = (m["releaseDate"] ?: m["release_date"]).cleanText()
                } else r.skipValue()
                "seasons" -> if (r.peek() == JsonReader.Token.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonReader.Token.BEGIN_OBJECT) r.readFlatObject()["season_number"].asInt()?.let { seasons += it }
                        else r.skipValue()
                    }
                    r.endArray()
                } else r.skipValue()
                "episodes" -> when (r.peek()) {
                    JsonReader.Token.BEGIN_OBJECT -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val hint = r.nextName().toIntOrNull()
                            if (r.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                r.beginArray(); while (r.hasNext()) readEpisodeOrSkip(r) { readEpisode(r, hint) }; r.endArray()
                            } else r.skipValue()
                        }
                        r.endObject()
                    }
                    JsonReader.Token.BEGIN_ARRAY -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            if (r.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                r.beginArray(); while (r.hasNext()) readEpisodeOrSkip(r) { readEpisode(r, null) }; r.endArray()
                            } else readEpisodeOrSkip(r) { readEpisode(r, null) }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        episodes.sortWith(compareBy({ it.season }, { it.episodeNum }))
        XSeriesInfo(plot, genre, release, seasons.toList(), episodes)
    }

    suspend fun vodInfo(streamId: Long): XVodInfo = request(api("get_vod_info", "vod_id" to streamId.toString())) { r ->
        var info: Map<String, String?> = emptyMap(); var backdrop: String? = null; var ext: String? = null
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); return@request XVodInfo(null, null, null, null, null, null, null, null) }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "info" -> if (r.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    info = r.readFlatObject { name, nr ->
                        if (name == "backdrop_path" && nr.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            nr.beginArray(); while (nr.hasNext()) { val v = nr.readScalar(); if (backdrop == null) backdrop = v.cleanText() }; nr.endArray(); true
                        } else false
                    }
                } else r.skipValue()
                "movie_data" -> if (r.peek() == JsonReader.Token.BEGIN_OBJECT) ext = r.readFlatObject()["container_extension"].cleanText() else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        XVodInfo(
            plot = (info["plot"] ?: info["description"]).cleanText(), genre = info["genre"].cleanText(),
            releaseDate = (info["releasedate"] ?: info["release_date"]).cleanText(),
            durationSecs = info["duration_secs"].asLong() ?: parseDuration(info["duration"]),
            cast = (info["cast"] ?: info["actors"]).cleanText(), director = info["director"].cleanText(),
            backdrop = backdrop, extension = ext,
        )
    }

    suspend fun shortEpg(streamId: Long, limit: Int = 2): List<XEpgEntry> =
        request(api("get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString())) { r ->
            val out = ArrayList<XEpgEntry>()
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); return@request out }
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "epg_listings" && r.peek() == JsonReader.Token.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
                        val m = r.readFlatObject()
                        val title = decodeMaybeBase64(m["title"]) ?: continue
                        out += XEpgEntry(title, decodeMaybeBase64(m["description"]),
                            m["start_timestamp"].asLong(), (m["stop_timestamp"] ?: m["end_timestamp"]).asLong())
                    }
                    r.endArray()
                } else r.skipValue()
            }
            r.endObject()
            out
        }

    // --- Playback URLs (built on demand, never persisted) ---

    fun liveUrl(streamId: Long, format: String): String = streamUrl("live", streamId, format)
    fun movieUrl(streamId: Long, ext: String?): String = streamUrl("movie", streamId, ext ?: "mp4")
    fun episodeUrl(episodeId: Long, ext: String?): String = streamUrl("series", episodeId, ext ?: "mp4")

    private fun streamUrl(kind: String, id: Long, ext: String): String =
        base.newBuilder().addPathSegment(kind).addPathSegment(config.username).addPathSegment(config.password)
            .addPathSegment("$id.${ext.trimStart('.')}").build().toString()

    // --- Plumbing ---

    private suspend fun <T> streamArray(
        url: HttpUrl, batchSize: Int, onBatch: suspend (List<T>) -> Unit, map: (Map<String, String?>) -> T?,
    ): Int {
        val response = execute(url)
        var count = 0
        try {
            response.use { resp ->
                val r = JsonReader.of(resp.body.source()) // strict: an HTML error page must not read as data
                when (r.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> Unit
                    // Some panels answer {} for an empty catalogue; anything else is not a catalogue.
                    JsonReader.Token.BEGIN_OBJECT -> { r.skipValue(); return 0 }
                    else -> throw XtreamException.Malformed(null)
                }
                r.beginArray()
                var batch = ArrayList<T>(batchSize)
                while (r.hasNext()) {
                    coroutineContext.ensureActive()
                    if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
                    map(r.readFlatObject())?.let { batch += it; count++ }
                    if (batch.size >= batchSize) { onBatch(batch); batch = ArrayList(batchSize) }
                }
                r.endArray()
                if (batch.isNotEmpty()) onBatch(batch)
            }
        } catch (e: JsonDataException) { throw XtreamException.Malformed(e) }
        catch (e: JsonEncodingException) { throw XtreamException.Malformed(e) }
        catch (e: XtreamException) { throw e }
        catch (e: java.io.EOFException) { throw XtreamException.Malformed(e) }
        catch (e: IOException) { if (e.isCleartextRefusal()) throw XtreamException.CleartextRefused(); throw XtreamException.Network(e) }
        return count
    }

    private suspend fun <T> request(url: HttpUrl, parse: (JsonReader) -> T): T {
        val response = execute(url)
        return withContext(Dispatchers.IO) {
            try {
                response.use { parse(JsonReader.of(it.body.source()).apply { isLenient = true }) }
            } catch (e: JsonDataException) { throw XtreamException.Malformed(e) }
            catch (e: JsonEncodingException) { throw XtreamException.Malformed(e) }
            catch (e: IllegalStateException) { throw XtreamException.Malformed(e) }
            catch (e: XtreamException) { throw e }
            catch (e: java.io.EOFException) { throw XtreamException.Malformed(e) }
            catch (e: IOException) { throw XtreamException.Network(e) }
        }
    }

    private suspend fun execute(url: HttpUrl): Response {
        if (url.scheme == "http" && !config.allowCleartext) throw XtreamException.CleartextRefused()
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        val response = try { call.await() } catch (e: IOException) {
            if (e.isCleartextRefusal()) throw XtreamException.CleartextRefused()
            throw XtreamException.Network(e)
        }
        if (!response.isSuccessful) {
            response.close()
            throw when (response.code) { 401, 403 -> XtreamException.Unauthorized(); else -> XtreamException.Http(response.code) }
        }
        return response
    }

    companion object {
        /** Accepts "host:port", "http://host:port/" or with a sub-path; strips player_api.php and query. */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() && !it.endsWith(".php") }
            return url.newBuilder().query(null).fragment(null).encodedPath("/").apply {
                segments.forEach { addPathSegment(it) }
                if (segments.isNotEmpty()) addPathSegment("") // keep a trailing slash: base is a directory
            }.build()
        }

        fun parseDuration(text: String?): Long? {
            val parts = text?.trim()?.split(":")?.mapNotNull { it.toLongOrNull() } ?: return null
            return when (parts.size) { 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; 2 -> parts[0] * 60 + parts[1]; else -> null }
        }

        fun decodeMaybeBase64(value: String?): String? {
            val v = value.cleanText() ?: return null
            return try {
                val bytes = Base64.getDecoder().decode(v)
                val decoded = String(bytes, Charsets.UTF_8)
                if (decoded.isNotBlank() && decoded.none { it == '�' || (it.isISOControl() && it != '\n') }) decoded.trim() else v
            } catch (_: IllegalArgumentException) { v }
        }
    }
}

private inline fun readEpisodeOrSkip(r: JsonReader, block: () -> Unit) {
    if (r.peek() == JsonReader.Token.BEGIN_OBJECT) block() else r.skipValue()
}

private fun IOException.isCleartextRefusal(): Boolean =
    this is CleartextRefusedIOException || message?.contains("CLEARTEXT", ignoreCase = true) == true

/** Raised by the app-wide interceptor when an http:// request has not been explicitly allowed. */
class CleartextRefusedIOException(host: String) : IOException("Unencrypted HTTP to $host is not allowed")

enum class CatalogKind(val categoriesAction: String, val key: String) {
    LIVE("get_live_categories", "live"), MOVIE("get_vod_categories", "movie"), SERIES("get_series_categories", "series");
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
