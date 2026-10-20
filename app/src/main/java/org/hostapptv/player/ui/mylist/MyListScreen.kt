package org.hostapptv.player.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.hostapptv.player.AppContainer
import org.hostapptv.player.Session
import org.hostapptv.player.container
import org.hostapptv.player.data.db.FavouriteEntity
import org.hostapptv.player.playback.ContentKind
import org.hostapptv.player.playback.PlaybackRequest
import org.hostapptv.player.ui.components.ChannelTile
import org.hostapptv.player.ui.components.PageHeading
import org.hostapptv.player.ui.components.PosterCard
import org.hostapptv.player.ui.components.SectionTitle
import org.hostapptv.player.ui.components.StatusMessage
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.shell.LocalNav
import org.hostapptv.player.ui.shell.Route
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens

/** A saved item resolved against the current catalogue; route is null when the provider no longer lists it. */
data class SavedItem(val fav: FavouriteEntity, val route: Route?, val channelId: Long?, val number: Int?)

class MyListViewModel(private val c: AppContainer, private val s: Session) : ViewModel() {
    val items = c.library.user.favourites(s.accountId).map { favs ->
        favs.map { f ->
            when (f.kind) {
                "live" -> {
                    val ch = f.itemKey.toLongOrNull()?.let { c.library.catalog.channel(s.accountId, it) }
                    SavedItem(f, if (ch != null) Route.Live else null, ch?.streamId, ch?.num)
                }
                "movie" -> {
                    val m = c.library.catalog.movieEditions(s.accountId, f.itemKey).firstOrNull()
                    SavedItem(f, m?.let { Route.MovieDetail(it.groupKey, it.streamId) }, null, null)
                }
                else -> {
                    val sr = c.library.catalog.seriesEditions(s.accountId, f.itemKey).firstOrNull()
                    SavedItem(f, sr?.let { Route.SeriesDetail(it.seriesId) }, null, null)
                }
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(f: FavouriteEntity) = viewModelScope.launch(Dispatchers.IO) { c.library.user.removeFavourite(f.accountId, f.kind, f.itemKey) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MyListScreen() {
    val nav = LocalNav.current
    val app = LocalContext.current.container
    val vm: MyListViewModel = viewModel(key = "mylist-${nav.session.accountId}") { MyListViewModel(app, nav.session) }
    val items by vm.items.collectAsState()
    val first = remember { FocusRequester() }
    val list = items ?: return
    val channels = list.filter { it.fav.kind == "live" }
    val movies = list.filter { it.fav.kind == "movie" }
    val series = list.filter { it.fav.kind == "series" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Dimens.gap, end = Dimens.safeH, top = Dimens.safeV, bottom = Dimens.safeV)) {
        item { PageHeading("My List", detail = if (list.isEmpty()) null else "${list.size} saved on this TV") }
        if (list.isEmpty()) {
            item {
                StatusMessage("Nothing saved yet", "Choose My List on a movie or series, or Add to favourites on a channel. Saved items stay on this TV for this provider account.") {
                    TvButton("Browse movies", { nav.goTop(Route.Movies) }, Modifier.focusRequester(first), icon = AppIcons.Movie)
                }
                LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
            }
        }
        if (channels.isNotEmpty()) item(key = "ch") {
            SectionTitle("Channels", trailing = "${channels.size}")
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
                items(channels, key = { it.fav.itemKey }) { it ->
                    ChannelTile(it.fav.title + if (it.route == null) " (unavailable)" else "", it.fav.image, it.number, {
                        if (it.channelId != null) nav.play(PlaybackRequest(ContentKind.LIVE, it.channelId, null, it.fav.title, it.number?.toString(), it.fav.image, favouritesOnly = true))
                        else vm.remove(it.fav)
                    })
                }
            }
        }
        listOf("Movies" to movies, "Series" to series).forEach { (label, group) ->
            if (group.isNotEmpty()) item(key = label) {
                SectionTitle(label, trailing = "${group.size}")
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(4.dp)) {
                    items(group, key = { it.fav.itemKey }) { it ->
                        PosterCard(
                            title = it.fav.title, image = it.fav.image,
                            caption = if (it.route == null) "No longer available · OK removes" else null,
                            onClick = { it.route?.let(nav.push) ?: vm.remove(it.fav) },
                        )
                    }
                }
            }
        }
    }
}
