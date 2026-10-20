package uk.ahmedhome.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import uk.ahmedhome.tv.AppContainer
import uk.ahmedhome.tv.Session
import uk.ahmedhome.tv.container
import uk.ahmedhome.tv.data.db.ProgressEntity
import uk.ahmedhome.tv.data.discovery.HomeDiscovery
import uk.ahmedhome.tv.playback.ContentKind
import uk.ahmedhome.tv.playback.PlaybackRequest
import uk.ahmedhome.tv.ui.components.TvFocusScrollSpec
import uk.ahmedhome.tv.ui.components.ButtonTone
import uk.ahmedhome.tv.ui.components.ChannelTile
import uk.ahmedhome.tv.ui.components.PosterCard
import uk.ahmedhome.tv.ui.components.SectionTitle
import uk.ahmedhome.tv.ui.components.SkeletonRow
import uk.ahmedhome.tv.ui.components.StatusMessage
import uk.ahmedhome.tv.ui.components.TvButton
import uk.ahmedhome.tv.ui.details.formatMinutes
import uk.ahmedhome.tv.ui.shell.LocalNav
import uk.ahmedhome.tv.ui.shell.Route
import uk.ahmedhome.tv.ui.theme.AppIcons
import uk.ahmedhome.tv.ui.theme.Dimens
import uk.ahmedhome.tv.ui.theme.Ink

class HomeViewModel(c: AppContainer, s: Session) : ViewModel() {
    private val a = s.accountId
    val continueWatching = c.library.user.continueWatching(a, 20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favouriteChannels = c.library.catalog.favouriteChannels(a).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recent = c.library.catalog.recentMovies(a, 24).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sync = c.sync.progress
    val discovery = c.discovery.available(a, c.library.catalog).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeDiscovery())
    var focusedFacts by mutableStateOf<String?>(null)
}

fun resumeRequest(p: ProgressEntity) = PlaybackRequest(
    kind = if (p.kind == "movie") ContentKind.MOVIE else ContentKind.EPISODE,
    contentId = p.contentId, ext = p.ext, title = p.title, subtitle = p.subtitle, image = p.image,
    groupKey = p.groupKey, seriesId = p.seriesId, season = p.season, episode = p.episode, startPositionMs = p.positionMs,
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val vm: HomeViewModel = viewModel(key = "home-${nav.session.accountId}") { HomeViewModel(app, nav.session) }
    val cw by vm.continueWatching.collectAsState()
    val fav by vm.favouriteChannels.collectAsState()
    val recent by vm.recent.collectAsState()
    val sync by vm.sync.collectAsState()
    val discovery by vm.discovery.collectAsState()
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val loaded = cw != null && fav != null && recent != null

    // Cold launch: the first useful Resume / favourite / title takes focus, never a skeleton.
    LaunchedEffect(loaded, discovery.feeds.any { it.titles.isNotEmpty() }) {
        if (loaded && !focusedOnce && (cw!!.isNotEmpty() || fav!!.isNotEmpty() || recent!!.isNotEmpty() || discovery.feeds.any { it.titles.isNotEmpty() })) {
            delay(80); runCatching { first.requestFocus() }; focusedOnce = true
        }
    }

    // A TV pivot leaves room above the focused poster for its row heading.
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvFocusScrollSpec) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("home-list"),
        contentPadding = PaddingValues(start = Dimens.gap, end = Dimens.safeH, top = Dimens.safeV, bottom = Dimens.safeV),
    ) {
        item(key = "welcome") {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("home-welcome")) {
                Text("Your next watch", style = MaterialTheme.typography.displaySmall, color = Ink.heading)
                Spacer(Modifier.height(4.dp))
                Text(
                    vm.focusedFacts ?: when {
                        sync.running -> "Saving your library to this TV${sync.current?.let { " · ${it.name.lowercase().replaceFirstChar(Char::uppercase)} ${sync.itemsInCurrent}" } ?: ""}…"
                        sync.error != null -> "Last refresh had a problem: ${sync.error}"
                        else -> "Football, family favourites and your next great series."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val cwList = cw.orEmpty()
        val favList = fav.orEmpty()
        val recentList = recent.orEmpty()
        var firstAssigned = false
        fun firstMod(): Modifier = if (!firstAssigned) { firstAssigned = true; Modifier.focusRequester(first) } else Modifier

        if (cwList.isNotEmpty()) {
            val m = firstMod()
            item(key = "cw") {
                SectionTitle("Continue Watching")
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    items(cwList, key = { "${it.kind}${it.contentId}" }) { p ->
                        val idx = cwList.indexOf(p)
                        PosterCard(
                            title = p.title, image = p.image,
                            caption = listOfNotNull(p.subtitle?.substringBefore(" · ", p.subtitle)?.takeIf { p.kind == "episode" }, "${formatMinutes(p.durationMs - p.positionMs)} left").joinToString(" · "),
                            progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null,
                            onClick = { nav.play(resumeRequest(p)) },
                            onFocused = { vm.focusedFacts = "Resume ${p.title}${p.subtitle?.let { " · $it" } ?: ""}" },
                            modifier = if (idx == 0) m else Modifier,
                        )
                    }
                }
            }
        }
        if (favList.isNotEmpty()) {
            val m = firstMod()
            item(key = "fav") {
                SectionTitle("Favourite Channels")
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    items(favList, key = { it.streamId }) { ch ->
                        ChannelTile(ch.name, ch.icon, ch.num, {
                            nav.play(PlaybackRequest(ContentKind.LIVE, ch.streamId, null, ch.name, ch.num?.toString(), ch.icon, favouritesOnly = true))
                        }, if (favList.first() == ch) m else Modifier, onFocused = { vm.focusedFacts = "Watch ${ch.name} live" })
                    }
                }
            }
        }
        if (discovery.feeds.isNotEmpty() && discovery.feeds.all { it.titles.isEmpty() }) {
            val m = firstMod()
            item(key = "discovery-empty") {
                val bring = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                StatusMessage("No discovery matches yet", "The sampled TMDB feeds have no matching titles in your saved provider library. You can still browse Movies and Series.", Modifier.bringIntoViewRequester(bring).testTag("discovery-empty")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Refresh discovery", { app.discovery.refresh(force = true) }, m.testTag("discovery-empty-action").onFocusChanged { if (it.isFocused) scope.launch { bring.bringIntoView() } }, icon = AppIcons.Refresh)
                        TvButton("Discovery settings", { nav.goTop(Route.Settings) }, icon = AppIcons.Settings)
                    }
                }
            }
        }
        discovery.feeds.filter { it.titles.isNotEmpty() }.forEach { available ->
            val feed = available.feed
            val m = if (available.titles.isNotEmpty()) firstMod() else Modifier
            item(key = "discovery-${feed.id}") {
                Column(Modifier.testTag("feed-${feed.id}")) {
                SectionTitle(feed.label, Modifier.testTag("feed-heading-${feed.id}"), trailing = if (feed.id.endsWith("new")) "Films & series · latest releases" else "TMDB · this week")
                if (available.titles.isEmpty()) {
                    Text("No matches in your library from this feed yet${if (sync.running) " · library is still updating" else ""}.",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                        items(available.titles, key = { it.metadata.key }) { item ->
                            val title = item.metadata
                            PosterCard(title = title.title, image = item.artwork,
                                caption = listOfNotNull(if (title.kind == "movie") "Film" else "Series", title.year?.toString()).joinToString(" · "),
                                onClick = { nav.push(if (title.kind == "movie") Route.MovieDetail(item.groupKey, item.providerId) else Route.SeriesDetail(item.providerId)) },
                                onFocused = {
                                    vm.focusedFacts = listOfNotNull(title.title, title.overview.takeIf { it.isNotBlank() }).joinToString(" · ")
                                },
                                modifier = (if (available.titles.first() == item) m else Modifier).testTag("feed-card-${feed.id}-${title.key}"))
                        }
                    }
                }
                if (System.currentTimeMillis() - feed.updatedAt > uk.ahmedhome.tv.data.discovery.DiscoveryRepository.REFRESH_INTERVAL) {
                    Text("Saved discovery · refresh unavailable", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                }
                }
            }
        }
        if (recentList.isNotEmpty()) {
            val m = firstMod()
            item(key = "recent") {
                SectionTitle("Recently Added", trailing = "Movies")
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    items(recentList, key = { it.streamId }) { mv ->
                        PosterCard(
                            title = mv.title, image = mv.poster, caption = mv.year?.toString(),
                            onClick = { nav.push(Route.MovieDetail(mv.groupKey, mv.streamId)) },
                            onFocused = { vm.focusedFacts = listOfNotNull(mv.title, mv.year?.toString(), if (mv.editionCount > 1) "${mv.editionCount} editions" else null).joinToString(" · ") },
                            modifier = if (recentList.first() == mv) m else Modifier,
                        )
                    }
                }
            }
        }
        item(key = "discovery-status") {
            Spacer(Modifier.height(14.dp))
            if (!discovery.source.configured) {
                val bring = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                StatusMessage("Discover something new", "Connect TMDB once in Settings for trending films and series, plus new and trending Korean and Chinese titles from your library.", Modifier.bringIntoViewRequester(bring).testTag("discovery-unconfigured")) {
                    TvButton("Set up discovery", { nav.goTop(Route.Settings) }, Modifier.onFocusChanged { if (it.isFocused) scope.launch { bring.bringIntoView() } }, icon = AppIcons.Settings)
                }
            } else {
                Text(discovery.source.error ?: if (discovery.source.loading) "Refreshing discovery… Saved titles stay available." else "Discovery by TMDB · shows titles matched to your provider library. New releases and weekly trends are separate feeds.",
                    style = MaterialTheme.typography.bodySmall, color = if (discovery.source.error != null) Ink.danger else Ink.muted)
                if (discovery.source.error != null) TvButton("Retry discovery", { app.discovery.refresh(force = true) }, icon = AppIcons.Refresh)
            }
        }
        if (!loaded || (sync.running && cwList.isEmpty() && favList.isEmpty() && recentList.isEmpty())) {
            item(key = "skeleton") { Spacer(Modifier.height(18.dp)); SkeletonRow() }
        } else if (cwList.isEmpty() && favList.isEmpty() && recentList.isEmpty() && discovery.feeds.all { it.titles.isEmpty() }) {
            val m = firstMod()
            item(key = "empty") {
                StatusMessage("Nothing to continue yet", "Browse Live TV or Movies. Favourites and anything you start watching will appear here.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Live TV", { nav.goTop(Route.Live) }, m, icon = AppIcons.Live, tone = ButtonTone.Primary)
                        TvButton("Movies", { nav.goTop(Route.Movies) }, icon = AppIcons.Movie)
                    }
                }
                LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
            }
        }
    }
    }
}
