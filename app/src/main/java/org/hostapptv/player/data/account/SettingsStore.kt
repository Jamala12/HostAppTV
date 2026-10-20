package org.hostapptv.player.data.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

data class AppSettings(
    val liveFormat: String = "ts",
    val preferredLanguage: String? = null,
    val preferredQuality: String? = null,
    val subtitleMode: String = "foreign",
)

/** Non-sensitive preferences. Credentials are never stored here. */
class SettingsStore(private val context: Context) {
    private val liveFormat = stringPreferencesKey("live_format")
    private val language = stringPreferencesKey("preferred_language")
    private val quality = stringPreferencesKey("preferred_quality")
    private val subtitles = stringPreferencesKey("subtitle_mode")

    private val recent = stringPreferencesKey("recent_searches")

    /** Recent local searches (newest first, at most 8). Search text only; never credentials. */
    val recentSearches: Flow<List<String>> = context.settingsStore.data.map { p ->
        p[recent]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addRecentSearch(q: String) {
        val query = q.trim().take(60)
        if (query.length < 2) return
        context.settingsStore.edit { p ->
            val list = (listOf(query) + (p[recent]?.split('\n') ?: emptyList())).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(8)
            p[recent] = list.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() { context.settingsStore.edit { it.remove(recent) } }

    val settings: Flow<AppSettings> = context.settingsStore.data.map {
        AppSettings(it[liveFormat] ?: "ts", it[language], it[quality], it[subtitles] ?: "foreign")
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val cur = AppSettings(p[liveFormat] ?: "ts", p[language], p[quality], p[subtitles] ?: "foreign")
            val next = transform(cur)
            p[liveFormat] = next.liveFormat
            if (next.preferredLanguage == null) p.remove(language) else p[language] = next.preferredLanguage
            if (next.preferredQuality == null) p.remove(quality) else p[quality] = next.preferredQuality
            p[subtitles] = next.subtitleMode
        }
    }
}
