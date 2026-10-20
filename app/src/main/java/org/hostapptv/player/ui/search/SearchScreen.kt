package org.hostapptv.player.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.AppContainer
import org.hostapptv.player.Session
import org.hostapptv.player.container
import org.hostapptv.player.data.repo.SearchQuery
import org.hostapptv.player.playback.ContentKind
import org.hostapptv.player.playback.PlaybackRequest
import org.hostapptv.player.ui.catalog.TitleCard
import org.hostapptv.player.ui.components.PosterCard
import org.hostapptv.player.ui.components.SectionTitle
import org.hostapptv.player.ui.components.StatusMessage
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.components.rememberImeDetacher
import org.hostapptv.player.ui.shell.LocalNav
import org.hostapptv.player.ui.shell.Route
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink

enum class Scope(val label: String) { ALL("All"), LIVE("Live TV"), MOVIES("Movies"), SERIES("Series") }

data class Counts(val live: Int, val movies: Int, val series: Int)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val c: AppContainer, private val s: Session) : ViewModel() {
    val text = MutableStateFlow("")
    val scope = MutableStateFlow(Scope.ALL)
    var lastFocusedKey: String? = null
    private val query = text.debounce(300).map { SearchQuery.from(it) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun <T : Any> paged(f: (SearchQuery) -> Flow<PagingData<T>>): Flow<PagingData<T>> =
        query.flatMapLatest { q -> if (q == null) flowOf(PagingData.empty()) else f(q) }.cachedIn(viewModelScope)

    val live = paged { q -> c.library.searchChannels(s.accountId, q).map { p -> p.map { TitleCard("l${it.streamId}", it.name, it.icon, it.num?.toString(), Route.Live) to (it as Any?) } } }
    val movies = paged { q -> c.library.searchMovies(s.accountId, q).map { p -> p.map { TitleCard("m${it.streamId}", it.title, it.poster, it.year?.toString(), Route.MovieDetail(it.groupKey, it.streamId)) to (null as Any?) } } }
    val series = paged { q -> c.library.searchSeries(s.accountId, q).map { p -> p.map { TitleCard("s${it.seriesId}", it.title, it.cover, it.year?.toString(), Route.SeriesDetail(it.seriesId)) to (null as Any?) } } }

    val counts = query.mapLatest { q ->
        if (q == null) null else withContext(Dispatchers.IO) {
            Counts(c.library.catalog.countChannels(s.accountId, q.pattern), c.library.catalog.countMovies(s.accountId, q.pattern), c.library.catalog.countSeries(s.accountId, q.pattern))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val coverage = c.library.catalog.syncStates(s.accountId).map { states ->
        val incomplete = listOf("live" to "Live TV", "movie" to "Movies", "series" to "Series")
            .filter { (k, _) -> states.none { it.kind == k && it.lastCompleteAt != null } }.map { it.second }
        if (incomplete.isEmpty()) null else "${incomplete.joinToString(", ")} still loading — results may be incomplete."
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent = c.settings.recentSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun remember(q: String) = viewModelScope.launch { c.settings.addRecentSearch(q) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val vm: SearchViewModel = viewModel(key = "search-${nav.session.accountId}") { SearchViewModel(app, nav.session) }
    val text by vm.text.collectAsState()
    val scope by vm.scope.collectAsState()
    val counts by vm.counts.collectAsState()
    val coverage by vm.coverage.collectAsState()
    val recent by vm.recent.collectAsState()
    val live = vm.live.collectAsLazyPagingItems()
    val movies = vm.movies.collectAsLazyPagingItems()
    val series = vm.series.collectAsLazyPagingItems()
    val field = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val detachIme = rememberImeDetacher()
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { field.requestFocus() } }

    fun open(card: TitleCard, ch: Any?) {
        vm.remember(text)
        vm.lastFocusedKey = card.key
        if (ch is org.hostapptv.player.data.db.ChannelEntity) nav.play(PlaybackRequest(ContentKind.LIVE, ch.streamId, null, ch.name, ch.num?.toString(), ch.icon))
        else nav.push(card.route)
    }

    Column(Modifier.fillMaxSize().padding(start = Dimens.gap, end = Dimens.safeH, top = Dimens.safeV)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(Dimens.controlHeight + 4.dp)
                    .background(Ink.control, RoundedCornerShape(Dimens.radiusMedium - 4.dp))
                    .border(BorderStroke(if (fieldFocused) Dimens.focusBorder else 1.dp, if (fieldFocused) Ink.focus else Ink.controlLine), RoundedCornerShape(Dimens.radiusMedium - 4.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Search, null, tint = Ink.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text("Search channels, movies and series", style = MaterialTheme.typography.bodyLarge, color = Ink.muted)
                        BasicTextField(
                            value = text, onValueChange = { vm.text.value = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text), cursorBrush = SolidColor(Ink.sage),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSearch = { vm.remember(text); fm.moveFocus(FocusDirection.Down); detachIme() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { if (fieldFocused && !it.isFocused) detachIme(); fieldFocused = it.isFocused }
                                .onPreviewKeyEvent { ev ->
                                    if (ev.key == Key.Enter || ev.key == Key.NumPadEnter) {
                                        if (ev.type == KeyEventType.KeyUp) { vm.remember(text); fm.moveFocus(FocusDirection.Down); detachIme() }
                                        true
                                    } else false
                                }
                                .semantics { contentDescription = "Search" },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Scope.entries.forEach { sc ->
                val n = counts?.let { c -> when (sc) { Scope.ALL -> c.live + c.movies + c.series; Scope.LIVE -> c.live; Scope.MOVIES -> c.movies; Scope.SERIES -> c.series } }
                TvButton(sc.label + (n?.let { " · $it" } ?: ""), { vm.scope.value = sc }, selected = sc == scope, icon = if (sc == scope) AppIcons.Check else null)
            }
        }
        coverage?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.muted) }
        Spacer(Modifier.height(4.dp))

        when {
            text.isBlank() -> {
                if (recent.isNotEmpty()) {
                    SectionTitle("Recent searches")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it }) { r -> TvButton(r, { vm.text.value = r }, icon = AppIcons.Search) }
                    }
                } else StatusMessage("Search everything on this TV", "Results come from your saved library, so they appear instantly and include Arabic and accented titles.")
            }
            counts?.let { it.live + it.movies + it.series == 0 } == true ->
                StatusMessage("No matches for “${text.trim()}”", "Check the spelling, try fewer words, or search another language.")
            scope == Scope.ALL -> LazyColumn(Modifier.focusRestorer(), contentPadding = PaddingValues(bottom = Dimens.safeV)) {
                if ((counts?.live ?: 0) > 0) item(key = "l") { ResultRow("Live TV", counts?.live, live, ::open) }
                if ((counts?.movies ?: 0) > 0) item(key = "m") { ResultRow("Movies", counts?.movies, movies, ::open) }
                if ((counts?.series ?: 0) > 0) item(key = "s") { ResultRow("Series", counts?.series, series, ::open) }
            }
            else -> {
                val items = when (scope) { Scope.LIVE -> live; Scope.MOVIES -> movies; else -> series }
                LazyVerticalGrid(
                    GridCells.Adaptive(Dimens.posterWidth + 4.dp), Modifier.fillMaxSize().focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = Dimens.safeV, start = 4.dp, end = 4.dp),
                ) {
                    items(items.itemCount, key = items.itemKey { it.first.key }) { i ->
                        val (card, ch) = items[i] ?: return@items
                        PosterCard(card.title, card.image, { open(card, ch) }, caption = card.caption,
                            height = if (scope == Scope.LIVE) Dimens.posterWidth else Dimens.posterHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, count: Int?, items: LazyPagingItems<Pair<TitleCard, Any?>>, open: (TitleCard, Any?) -> Unit) {
    SectionTitle(title, trailing = count?.let { "$it result${if (it == 1) "" else "s"}" })
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        items(items.itemCount, key = items.itemKey { it.first.key }) { i ->
            val (card, ch) = items[i] ?: return@items
            PosterCard(card.title, card.image, { open(card, ch) }, caption = card.caption,
                height = if (ch != null) Dimens.posterWidth else Dimens.posterHeight)
        }
    }
}
