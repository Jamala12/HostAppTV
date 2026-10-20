package org.hostapptv.player.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.hostapptv.player.AppContainer
import org.hostapptv.player.Session
import org.hostapptv.player.container
import org.hostapptv.player.data.repo.SortOrder
import org.hostapptv.player.ui.components.ButtonTone
import org.hostapptv.player.ui.components.PageHeading
import org.hostapptv.player.ui.components.PosterCard
import org.hostapptv.player.ui.components.StatusMessage
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.shell.LocalNav
import org.hostapptv.player.ui.shell.Route
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink

enum class CatalogKindUi(val title: String, val dbKind: String) { MOVIES("Movies", "movie"), SERIES("Series", "series") }

/** A grid card, independent of entity type. */
data class TitleCard(val key: String, val title: String, val image: String?, val caption: String?, val route: Route)

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(private val c: AppContainer, private val s: Session, val kind: CatalogKindUi) : ViewModel() {
    val category = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(SortOrder.RECENT)
    var lastFocusedKey: String? = null

    val categories = c.library.catalog.categories(s.accountId, kind.dbKind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: Flow<PagingData<TitleCard>> = combine(category, sort) { cat, so -> cat to so }.flatMapLatest { (cat, so) ->
        when (kind) {
            CatalogKindUi.MOVIES -> c.library.movies(s.accountId, cat, so).map { page ->
                page.map { m -> TitleCard("m${m.streamId}", m.title, m.poster, captionFor(m.year, m.editionCount), Route.MovieDetail(m.groupKey, m.streamId)) }
            }
            CatalogKindUi.SERIES -> c.library.series(s.accountId, cat, so).map { page ->
                page.map { m -> TitleCard("s${m.seriesId}", m.title, m.cover, captionFor(m.year, m.editionCount), Route.SeriesDetail(m.seriesId)) }
            }
        }
    }.cachedIn(viewModelScope)

    val count = category.flatMapLatest { cat ->
        if (kind == CatalogKindUi.MOVIES) c.library.catalog.movieCount(s.accountId, cat) else c.library.catalog.seriesCount(s.accountId, cat)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val sync = c.sync.progress

    private fun captionFor(year: Int?, editions: Int) = listOfNotNull(year?.toString(), if (editions > 1) "$editions editions" else null).joinToString(" · ").ifBlank { null }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(kind: CatalogKindUi) {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val vm: CatalogViewModel = viewModel(key = "catalog-${kind.name}-${nav.session.accountId}") { CatalogViewModel(app, nav.session, kind) }
    val items = vm.items.collectAsLazyPagingItems()
    val count by vm.count.collectAsState()
    val category by vm.category.collectAsState()
    val categories by vm.categories.collectAsState()
    val sort by vm.sort.collectAsState()
    val sync by vm.sync.collectAsState()
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val filterButton = remember { FocusRequester() }
    val panelFirst = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    var restoreKey by remember { mutableStateOf(vm.lastFocusedKey) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(restoreKey, items.itemCount) {
        if (restoreKey != null && items.itemCount > 0) { delay(50); runCatching { restoreRequester.requestFocus() }; restoreKey = null }
    }
    LaunchedEffect(panelOpen) { if (panelOpen) runCatching { panelFirst.requestFocus() } }
    BackHandler(enabled = panelOpen) { panelOpen = false; runCatching { filterButton.requestFocus() } }

    val categoryName = categories.firstOrNull { it.categoryId == category }?.name
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = Dimens.gap, end = Dimens.safeH, top = Dimens.safeV)) {
            Row(verticalAlignment = Alignment.Bottom) {
                PageHeading(kind.title, Modifier.weight(1f), detail = listOfNotNull(
                    if (count > 0) "$count titles" else null, categoryName,
                    if (sync.running) "library still loading" else null,
                ).joinToString(" · ").ifBlank { null })
                TvButton(categoryName ?: "All categories", { panelOpen = true }, Modifier.focusRequester(filterButton), icon = AppIcons.Filter, selected = category != null)
                Spacer(Modifier.width(10.dp))
                TvButton(sort.label, { vm.sort.value = SortOrder.entries[(sort.ordinal + 1) % SortOrder.entries.size] })
            }
            Spacer(Modifier.height(16.dp))
            if (items.itemCount == 0) {
                StatusMessage(
                    title = if (sync.running) "Loading ${kind.title.lowercase()}…" else "Nothing here yet",
                    body = if (sync.running) "Your provider's catalogue is being saved to this TV. Titles appear as they arrive."
                    else if (category != null) "No titles in this category. Choose another category." else "Refresh the library from Settings if your provider has ${kind.title.lowercase()}.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimens.posterWidth + 4.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = Dimens.safeV, start = 4.dp, end = 4.dp),
                ) {
                    items(items.itemCount, key = items.itemKey { it.key }) { i ->
                        val card = items[i] ?: return@items
                        PosterCard(
                            title = card.title, image = card.image, caption = card.caption,
                            onClick = { nav.push(card.route) },
                            onFocused = { vm.lastFocusedKey = card.key },
                            modifier = if (card.key == restoreKey) Modifier.focusRequester(restoreRequester) else Modifier,
                        )
                    }
                }
            }
        }
        if (panelOpen) {
            Column(Modifier.align(Alignment.CenterEnd).width(320.dp).fillMaxHeight().background(Ink.surface)
                .focusGroup().focusProperties { onExit = { cancelFocusChange() } }
                .padding(horizontal = 22.dp, vertical = Dimens.safeV)) {
                Text("Category", style = MaterialTheme.typography.headlineSmall, color = Ink.heading)
                Text("Back closes without changes", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        TvButton("All categories", { vm.category.value = null; panelOpen = false; runCatching { filterButton.requestFocus() } },
                            Modifier.fillMaxWidth().focusRequester(panelFirst), icon = if (category == null) AppIcons.Check else null, selected = category == null, tone = ButtonTone.Secondary)
                    }
                    items(categories, key = { it.categoryId }) { c ->
                        TvButton(c.name, { vm.category.value = c.categoryId; panelOpen = false; runCatching { filterButton.requestFocus() } },
                            Modifier.fillMaxWidth(), icon = if (category == c.categoryId) AppIcons.Check else null, selected = category == c.categoryId)
                    }
                }
            }
        }
    }
}
