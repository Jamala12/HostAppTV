package uk.ahmedhome.tv.core

import java.text.Normalizer

/** Search key normalisation shared by indexing and querying. */
object TextNormalizer {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    // Arabic: tatweel and harakat are removed; alef/yeh/teh-marbuta variants unify.
    private val arabicMap = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا', 'ى' to 'ي', 'ة' to 'ه', 'ؤ' to 'و', 'ئ' to 'ي')

    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        val stripped = combiningMarks.replace(decomposed, "")
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            if (c == 'ـ') continue
            sb.append(arabicMap[c] ?: c)
        }
        return nonWord.replace(sb.toString().lowercase(), " ").trim()
    }

    /** Escapes LIKE wildcards; the DAO uses ESCAPE '\'. */
    fun likeEscape(normalized: String): String =
        normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
