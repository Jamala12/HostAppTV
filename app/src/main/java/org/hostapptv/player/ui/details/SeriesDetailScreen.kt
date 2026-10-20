package org.hostapptv.player.ui.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.container
import org.hostapptv.player.data.db.ProgressEntity
import org.hostapptv.player.data.db.SeriesEntity
import org.hostapptv.player.data.xtream.XEpisode
import org.hostapptv.player.data.xtream.XSeriesInfo
import org.hostapptv.player.playback.ContentKind
import org.hostapptv.player.playback.PlaybackRequest
import org.hostapptv.player.ui.components.Artwork
import org.hostapptv.player.ui.components.ButtonTone
import org.hostapptv.player.ui.components.ProgressLine
import org.hostapptv.player.ui.components.StatusMessage
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.shell.LocalNav
import org.hostapptv.player.ui.shell.Route
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink

/** Next episode to offer: the most recently watched unfinished one, else the one after the last finished, else the first. */
fun nextUp(episodes: List<XEpisode>, progress: List<ProgressEntity>): Pair<XEpisode, ProgressEntity?>? {
    if (episodes.isEmpty()) return null
    val byId = progress.associateBy { it.contentId }
    val latest = progress.maxByOrNull { it.updatedAt } ?: return episodes.first() to null
    val idx = episodes.indexOfFirst { it.id == latest.contentId }
    if (idx < 0) return episodes.first() to null
    return if (!latest.finished) episodes[idx] to latest
    else episodes.getOrNull(idx + 1)?.let { it to byId[it.id] } ?: (episodes[idx] to latest)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesDetailScreen(route: Route.SeriesDetail) {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val a = nav.session.accountId
    val scope = rememberCoroutineScope()
    var seriesId by rememberSaveable { mutableStateOf(route.seriesId) }
    var series by remember { mutableStateOf<SeriesEntity?>(null) }
    var editions by remember { mutableStateOf<List<SeriesEntity>>(emptyList()) }
    var info by remember { mutableStateOf<XSeriesInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loadedSeries by remember { mutableStateOf(false) }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(seriesId) {
        info = null; failed = false
        val s = withContext(Dispatchers.IO) { app.library.catalog.seriesById(a, seriesId) }
        series = s; loadedSeries = true
        if (s != null) editions = withContext(Dispatchers.IO) { app.library.catalog.seriesEditions(a, s.groupKey) }
        info = withContext(Dispatchers.IO) { runCatching { nav.session.client.seriesInfo(seriesId) }.getOrNull() }
        if (info == null) failed = true
    }
    val progress by remember(seriesId) { app.library.user.seriesProgress(a, seriesId) }.collectAsState(emptyList())
    val s = series
    if (!loadedSeries) { Box(Modifier.fillMaxSize()); return }
    if (s == null) {
        Column(Modifier.padding(horizontal = Dimens.safeH, vertical = Dimens.safeV)) {
            StatusMessage("This series is no longer in your provider's catalogue", "It may have been removed during a library refresh.", isError = true)
        }
        return
    }
    val isFav by remember(s.groupKey) { app.library.user.isFavourite(a, "series", s.groupKey) }.collectAsState(false)
    val episodes = info?.episodes.orEmpty()
    val seasons = info?.seasons?.filter { sn -> episodes.any { it.season == sn } }?.ifEmpty { null } ?: episodes.map { it.season }.distinct()
    val up = nextUp(episodes, progress)
    val currentSeason = season ?: up?.first?.season ?: seasons.firstOrNull()
    val progressById = progress.associateBy { it.contentId }

    LaunchedEffect(info != null) { if (info != null) runCatching { primaryFocus.requestFocus() } }

    fun play(ep: XEpisode, resumeAt: Long) = nav.play(PlaybackRequest(
        ContentKind.EPISODE, ep.id, ep.extension, s.title, subtitle = "S${ep.season} · E${ep.episodeNum} · ${ep.title}",
        image = s.cover, groupKey = s.groupKey, seriesId = s.seriesId, season = ep.season, episode = ep.episodeNum,
        startPositionMs = resumeAt,
    ))

    Row(Modifier.fillMaxSize().padding(horizontal = Dimens.safeH, vertical = Dimens.safeV)) {
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            Row {
                Artwork(s.cover, s.title, 110.dp, 165.dp)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, color = Ink.heading, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(listOfNotNull(s.year?.toString(), info?.genre ?: s.genre, s.rating?.takeIf { it > 0 }?.let { "Rating %.1f".format(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 2)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(info?.plot ?: s.plot ?: if (failed) "Episode list unavailable from the provider." else "Loading episodes…",
                style = MaterialTheme.typography.bodyMedium, color = Ink.text, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            if (up != null) {
                val (ep, p) = up
                val resumeAt = p?.takeIf { !it.finished }?.positionMs ?: 0L
                TvButton(
                    if (resumeAt > 0) "Resume S${ep.season} E${ep.episodeNum}" else if (p?.finished == true || progress.isNotEmpty()) "Next: S${ep.season} E${ep.episodeNum}" else "Play S${ep.season} E${ep.episodeNum}",
                    { play(ep, resumeAt) }, Modifier.focusRequester(primaryFocus), icon = AppIcons.Play, tone = ButtonTone.Primary,
                )
                Spacer(Modifier.height(10.dp))
            }
            TvButton(if (isFav) "In My List" else "My List", {
                scope.launch(Dispatchers.IO) { app.library.toggleFavourite(a, "series", s.groupKey, s.title, s.cover, isFav) }
            }, icon = if (isFav) AppIcons.Check else AppIcons.Plus, selected = isFav)
            if (editions.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text("Editions", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(editions, key = { it.seriesId }) { e ->
                        TvButton(editionLabel(e.language, e.quality, null), { seriesId = e.seriesId; season = null },
                            icon = if (e.seriesId == seriesId) AppIcons.Check else null, selected = e.seriesId == seriesId)
                    }
                }
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (seasons.isNotEmpty()) {
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                    items(seasons, key = { it }) { sn ->
                        TvButton("Season $sn", { season = sn }, selected = sn == currentSeason)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val list = episodes.filter { it.season == currentSeason }
            if (info != null && list.isEmpty()) StatusMessage("No episodes listed", "The provider returned no episodes for this season.")
            LazyColumn(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = Dimens.safeV, top = 2.dp, start = 2.dp, end = 2.dp)) {
                items(list, key = { it.id }) { ep ->
                    val p = progressById[ep.id]
                    EpisodeRow(ep, p) { play(ep, p?.takeIf { !it.finished }?.positionMs ?: 0L) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: XEpisode, p: ProgressEntity?, onClick: () -> Unit) {
    val state = when { p?.finished == true -> "Watched"; p != null && p.durationMs > 0 -> "${formatMinutes(p.durationMs - p.positionMs)} left"; else -> null }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Episode ${ep.episodeNum}, ${ep.title}${state?.let { ", $it" } ?: ""}" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.surface, focusedContainerColor = Ink.selected, contentColor = Ink.text, focusedContentColor = Ink.text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ep.episodeNum}", style = MaterialTheme.typography.titleMedium, color = Ink.muted, modifier = Modifier.width(36.dp))
                Text(ep.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                listOfNotNull(ep.durationSecs?.let { "${it / 60} min" }, state).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (p?.finished == true) Ink.sage else Ink.muted)
                }
            }
            if (p != null && !p.finished && p.durationMs > 0) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(p.positionMs.toFloat() / p.durationMs, Modifier.fillMaxWidth().padding(start = 36.dp))
            }
        }
    }
}
