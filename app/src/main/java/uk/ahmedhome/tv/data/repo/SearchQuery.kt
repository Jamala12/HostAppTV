package uk.ahmedhome.tv.data.repo

import uk.ahmedhome.tv.core.TextNormalizer

/** LIKE patterns for ranked exact / prefix / word-prefix / token-contains matching. */
data class SearchQuery(val q: String, val pattern: String, val prefix: String, val wordPrefix: String) {
    companion object {
        fun from(raw: String): SearchQuery? {
            val q = TextNormalizer.normalize(raw)
            if (q.isEmpty()) return null
            val tokens = q.split(' ').filter { it.isNotEmpty() }.map(TextNormalizer::likeEscape)
            val escaped = TextNormalizer.likeEscape(q)
            return SearchQuery(q, "%" + tokens.joinToString("%") + "%", "$escaped%", "% $escaped%")
        }
    }
}
