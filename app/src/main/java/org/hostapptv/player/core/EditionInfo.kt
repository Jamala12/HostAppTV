package org.hostapptv.player.core

/**
 * Derives display title, language and quality from provider titles such as
 * "EN - The Film (2019) 4K", "|FR| Le Film [FHD]" or "AR: فيلم". Unknown stays unknown;
 * nothing is guessed from missing data.
 */
data class EditionInfo(val title: String, val language: String?, val quality: String?, val year: Int?)

object EditionParser {
    private val prefix = Regex("""^\s*[|\[(]?\s*([A-Z]{2,5}(?:-[A-Z]{2,3})?)\s*[|\]):\-–]+\s*""")
    private val qualityTokens = linkedMapOf(
        "4K" to Regex("""(?i)(?:\b|_)(4k|uhd|2160p?)(?:\b|_)"""),
        "1080p" to Regex("""(?i)(?:\b|_)(fhd|1080p?|full ?hd)(?:\b|_)"""),
        "720p" to Regex("""(?i)(?:\b|_)(hd|720p?)(?:\b|_)"""),
        "SD" to Regex("""(?i)(?:\b|_)(sd|480p?|576p?)(?:\b|_)"""),
    )
    private val bracketed = Regex("""\s*[\[(|]\s*(?:4k|uhd|fhd|hd|sd|hevc|h\.?265|multi|vostfr|1080p?|720p?|2160p?)\s*[\])|]?\s*""", RegexOption.IGNORE_CASE)
    private val trailingQuality = Regex("""(?i)\s*[-–|]?\s*\b(4k|uhd|fhd|hd|sd|hevc|1080p|720p|2160p)\s*$""")
    private val yearRegex = Regex("""[\s(\[\-]((?:19|20)\d{2})[)\]]?\s*$""")
    private val knownLanguages = setOf(
        "EN", "UK", "US", "FR", "DE", "ES", "IT", "PT", "NL", "AR", "TR", "PL", "RU", "HI", "UR", "BN", "PK",
        "IN", "SE", "NO", "DK", "FI", "GR", "RO", "AL", "KU", "FA", "IR", "MULTI", "SO", "PER", "ENG", "ARA", "FRE", "GER", "SPA", "ITA",
    )

    fun parse(raw: String?, providerYear: String? = null): EditionInfo {
        var name = raw.orEmpty().trim()
        var language: String? = null
        prefix.find(name)?.let { m ->
            val code = m.groupValues[1].uppercase()
            if (code in knownLanguages || code.substringBefore('-') in knownLanguages) {
                language = code
                name = name.substring(m.range.last + 1)
            }
        }
        var quality: String? = null
        for ((label, rx) in qualityTokens) {
            if (rx.containsMatchIn(name)) { quality = label; break }
        }
        name = bracketed.replace(name, " ")
        name = trailingQuality.replace(name, "")
        var year = providerYear?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        yearRegex.find(name)?.let { m ->
            if (year == null) year = m.groupValues[1].toInt()
            name = name.substring(0, m.range.first)
        }
        val title = name.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '|', ':').ifBlank { raw.orEmpty().trim() }
        return EditionInfo(title, language, quality, year)
    }

    /** Editions group only by media type plus a valid TMDB id; everything else stays separate. */
    fun groupKey(kind: String, tmdbId: String?, providerId: Long): String {
        val tmdb = tmdbId?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        return if (tmdb != null) "$kind:tmdb:$tmdb" else "$kind:id:$providerId"
    }

    fun qualityRank(quality: String?): Int = when (quality) {
        "4K" -> 4; "1080p" -> 3; "720p" -> 2; "SD" -> 1; else -> 0
    }
}
