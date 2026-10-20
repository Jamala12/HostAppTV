package org.hostapptv.player.data.xtream

data class ProviderConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val allowCleartext: Boolean,
) {
    val isCleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)
    override fun toString(): String = "ProviderConfig(baseUrl=$baseUrl, username=***, password=***)"
}

data class AccountInfo(
    val active: Boolean,
    val status: String?,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val outputFormats: List<String>,
)

data class XCategory(val id: String, val name: String, val order: Int)

data class XLiveStream(
    val streamId: Long, val num: Int?, val name: String, val icon: String?, val epgChannelId: String?,
    val categoryId: String?, val tvArchive: Boolean, val added: Long?,
)

data class XMovie(
    val streamId: Long, val name: String, val icon: String?, val categoryId: String?, val rating: Double?,
    val added: Long?, val extension: String?, val tmdbId: String?, val year: String?,
)

data class XSeries(
    val seriesId: Long, val name: String, val cover: String?, val plot: String?, val categoryId: String?,
    val rating: Double?, val lastModified: Long?, val tmdbId: String?, val year: String?, val genre: String?,
)

data class XEpisode(
    val id: Long, val season: Int, val episodeNum: Int, val title: String, val extension: String?,
    val durationSecs: Long?, val plot: String?, val image: String?,
)

data class XSeriesInfo(val plot: String?, val genre: String?, val releaseDate: String?, val seasons: List<Int>, val episodes: List<XEpisode>)

data class XVodInfo(
    val plot: String?, val genre: String?, val releaseDate: String?, val durationSecs: Long?, val cast: String?,
    val director: String?, val backdrop: String?, val extension: String?,
)

data class XEpgEntry(val title: String, val description: String?, val startEpochSec: Long?, val endEpochSec: Long?)

sealed class XtreamException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : XtreamException("Network unavailable or provider unreachable", cause)
    class Unauthorized : XtreamException("The provider rejected these account details")
    class AccountInactive(val status: String?) : XtreamException("Account is not active" + (status?.let { " ($it)" } ?: ""))
    class Http(val code: Int) : XtreamException("Provider returned HTTP $code")
    class Malformed(cause: Throwable?) : XtreamException("Provider response could not be read", cause)
    class CleartextRefused : XtreamException("Unencrypted HTTP is not allowed for this provider")
    /** A catalogue that had items came back empty; the saved library is kept rather than pruned. */
    class SuspiciousEmpty : XtreamException("The provider returned an empty list; the saved library was kept")
}
