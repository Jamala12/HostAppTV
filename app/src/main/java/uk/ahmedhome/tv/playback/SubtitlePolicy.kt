package uk.ahmedhome.tv.playback

/** Household default: English subtitles for known foreign-language audio; never invent track language. */
object SubtitlePolicy {
    val modes = listOf("foreign", "always", "off")
    fun isEnglish(language: String?): Boolean = language?.lowercase()?.substringBefore('-')?.substringBefore('_') in setOf("en", "eng")
    fun wantsEnglish(mode: String, selectedAudioLanguages: List<String?>): Boolean = when (mode) {
        "always" -> true
        "off" -> false
        else -> selectedAudioLanguages.filterNotNull().filter { it.isNotBlank() && it != "und" }
            .let { it.isNotEmpty() && it.none(::isEnglish) }
    }
    fun label(mode: String): String = when (mode) {
        "always" -> "English when available"
        "off" -> "Off"
        else -> "English for foreign audio"
    }
}
