package org.hostapptv.player.playback

import android.content.Context
import android.content.Intent

enum class ContentKind { LIVE, MOVIE, EPISODE }

/** What to play, by provider ID. URLs are built at play time from the active session, never stored or passed around. */
data class PlaybackRequest(
    val kind: ContentKind,
    val contentId: Long,
    val ext: String?,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val groupKey: String? = null,
    val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val startPositionMs: Long = 0,
    /** Live: the browsing context used for channel up/down. */
    val categoryId: String? = null,
    val favouritesOnly: Boolean = false,
) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putExtra("kind", kind.name).putExtra("id", contentId).putExtra("ext", ext).putExtra("title", title)
        .putExtra("subtitle", subtitle).putExtra("image", image).putExtra("groupKey", groupKey)
        .putExtra("seriesId", seriesId ?: -1L).putExtra("season", season ?: -1).putExtra("episode", episode ?: -1)
        .putExtra("start", startPositionMs).putExtra("categoryId", categoryId).putExtra("favouritesOnly", favouritesOnly)

    companion object {
        fun fromIntent(i: Intent): PlaybackRequest? {
            val kind = i.getStringExtra("kind")?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() } ?: return null
            val id = i.getLongExtra("id", -1L).takeIf { it >= 0 } ?: return null
            return PlaybackRequest(
                kind, id, i.getStringExtra("ext"), i.getStringExtra("title") ?: "", i.getStringExtra("subtitle"),
                i.getStringExtra("image"), i.getStringExtra("groupKey"),
                i.getLongExtra("seriesId", -1L).takeIf { it >= 0 }, i.getIntExtra("season", -1).takeIf { it >= 0 },
                i.getIntExtra("episode", -1).takeIf { it >= 0 }, i.getLongExtra("start", 0L),
                i.getStringExtra("categoryId"), i.getBooleanExtra("favouritesOnly", false),
            )
        }
    }
}
