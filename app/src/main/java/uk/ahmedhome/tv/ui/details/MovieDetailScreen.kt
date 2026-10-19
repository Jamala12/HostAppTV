package uk.ahmedhome.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.ahmedhome.tv.container
import uk.ahmedhome.tv.core.EditionParser
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.xtream.XVodInfo
import uk.ahmedhome.tv.playback.ContentKind
import uk.ahmedhome.tv.playback.PlaybackRequest
import uk.ahmedhome.tv.ui.components.Artwork
import uk.ahmedhome.tv.ui.components.ButtonTone
import uk.ahmedhome.tv.ui.components.ProgressLine
import uk.ahmedhome.tv.ui.components.StatusMessage
import uk.ahmedhome.tv.ui.components.TvButton
import uk.ahmedhome.tv.ui.shell.LocalNav
import uk.ahmedhome.tv.ui.shell.Route
import uk.ahmedhome.tv.ui.theme.AppIcons
import uk.ahmedhome.tv.ui.theme.Dimens
import uk.ahmedhome.tv.ui.theme.Ink

fun editionLabel(language: String?, quality: String?, ext: String?): String =
    listOf(language ?: "Unknown language", quality ?: "Unknown quality", ext?.uppercase()).filterNotNull().joinToString(" · ")

@Composable
fun MovieDetailScreen(route: Route.MovieDetail) {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val a = nav.session.accountId
    val scope = rememberCoroutineScope()
    var editions by remember { mutableStateOf<List<MovieEntity>?>(null) }
    var selectedId by rememberSaveable { mutableStateOf(route.streamId) }
    var info by remember { mutableStateOf<XVodInfo?>(null) }
    var infoFailed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(route.groupKey) {
        val list = withContext(Dispatchers.IO) { app.library.catalog.movieEditions(a, route.groupKey) }
        val prefs = app.settings.current()
        editions = list
        if (list.none { it.streamId == selectedId } && list.isNotEmpty()) {
            selectedId = list.maxByOrNull { (if (prefs.preferredLanguage != null && it.language == prefs.preferredLanguage) 10 else 0) + EditionParser.qualityRank(it.quality) }!!.streamId
        }
    }
    LaunchedEffect(selectedId) {
        info = null; infoFailed = false
        info = withContext(Dispatchers.IO) { runCatching { nav.session.client.vodInfo(selectedId) }.getOrNull() }
        if (info == null) infoFailed = true
    }
    LaunchedEffect(editions != null) { if (editions != null) runCatching { playFocus.requestFocus() } }

    val list = editions
    if (list == null) { Box(Modifier.fillMaxSize()); return }
    val movie = list.firstOrNull { it.streamId == selectedId } ?: list.firstOrNull()
    if (movie == null) {
        Column(Modifier.padding(horizontal = Dimens.safeH, vertical = Dimens.safeV)) {
            StatusMessage("This title is no longer in your provider's catalogue", "It may have been removed during a library refresh.", isError = true) {
                TvButton("Back", { nav.focusRail() }, icon = AppIcons.Back)
            }
        }
        return
    }
    val progress by remember(movie.streamId) { app.library.user.progressFlow(a, "movie", movie.streamId) }.collectAsState(null)
    val isFav by remember(route.groupKey) { app.library.user.isFavourite(a, "movie", route.groupKey) }.collectAsState(false)
    val resumeAt = progress?.takeIf { !it.finished && it.positionMs > 0 }?.positionMs

    fun play(fromStart: Boolean) = nav.play(PlaybackRequest(
        ContentKind.MOVIE, movie.streamId, info?.extension ?: movie.ext, movie.title,
        subtitle = editionLabel(movie.language, movie.quality, null), image = movie.poster, groupKey = movie.groupKey,
        startPositionMs = if (fromStart) 1 else (resumeAt ?: 0),
    ))

    Box(Modifier.fillMaxSize()) {
        info?.backdrop?.let {
            Artwork(it, "", 960.dp, 540.dp, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.canvas, Ink.canvas.copy(alpha = 0.93f), Ink.canvas.copy(alpha = 0.6f)))))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = Dimens.safeH, vertical = Dimens.safeV)) {
            Artwork(movie.poster, movie.title, 200.dp, 300.dp)
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.title, style = MaterialTheme.typography.displaySmall, color = Ink.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(movie.year?.toString(), info?.durationSecs?.let { "${it / 60} min" }, info?.genre, movie.rating?.takeIf { it > 0 }?.let { "Rating %.1f".format(it) })
                        .joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted, maxLines = 1,
                )
                Spacer(Modifier.height(14.dp))
                val plot = info?.plot ?: if (infoFailed) "Details unavailable from the provider." else "Loading details…"
                Text(plot, style = MaterialTheme.typography.bodyMedium, color = Ink.text, maxLines = if (expanded) 12 else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
                Spacer(Modifier.height(20.dp))
                if (resumeAt != null && progress!!.durationMs > 0) {
                    ProgressLine(resumeAt.toFloat() / progress!!.durationMs, Modifier.width(320.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (resumeAt != null) "Resume from ${formatPosition(resumeAt)}" else "Play", { play(false) }, Modifier.focusRequester(playFocus), icon = AppIcons.Play, tone = ButtonTone.Primary)
                    if (resumeAt != null) TvButton("Start over", { play(true) }, icon = AppIcons.Refresh)
                    TvButton(if (isFav) "In My List" else "My List", {
                        scope.launch(Dispatchers.IO) { app.library.toggleFavourite(a, "movie", route.groupKey, movie.title, movie.poster, isFav) }
                    }, icon = if (isFav) AppIcons.Check else AppIcons.Plus, selected = isFav)
                    if ((info?.plot?.length ?: 0) > 220) TvButton(if (expanded) "Less" else "More", { expanded = !expanded })
                }
                if (list.size > 1) {
                    Spacer(Modifier.height(22.dp))
                    Text("Editions", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.streamId }) { e ->
                            TvButton(editionLabel(e.language, e.quality, e.ext), { selectedId = e.streamId },
                                icon = if (e.streamId == movie.streamId) AppIcons.Check else null, selected = e.streamId == movie.streamId)
                        }
                    }
                } else {
                    Spacer(Modifier.height(18.dp))
                    Text(editionLabel(movie.language, movie.quality, movie.ext), style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                }
                info?.cast?.let { Spacer(Modifier.height(16.dp)); Text("Cast  $it", style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

fun formatMinutes(ms: Long): String {
    val m = ms / 60_000
    return when { m >= 60 -> "${m / 60}h ${m % 60}m"; m >= 1 -> "${m}m"; else -> "<1m" }
}

/** Exact resume point, e.g. "0:25" or "1:02:10". */
fun formatPosition(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

