package uk.ahmedhome.tv.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import uk.ahmedhome.tv.AppContainer
import uk.ahmedhome.tv.Session
import uk.ahmedhome.tv.container
import uk.ahmedhome.tv.data.db.ChannelEntity
import uk.ahmedhome.tv.data.xtream.XEpgEntry
import uk.ahmedhome.tv.playback.ContentKind
import uk.ahmedhome.tv.playback.PlaybackRequest
import uk.ahmedhome.tv.ui.components.Artwork
import uk.ahmedhome.tv.ui.components.ButtonTone
import uk.ahmedhome.tv.ui.components.LiveBadge
import uk.ahmedhome.tv.ui.components.PageHeading
import uk.ahmedhome.tv.ui.components.StatusMessage
import uk.ahmedhome.tv.ui.components.TvButton
import uk.ahmedhome.tv.ui.shell.LocalNav
import uk.ahmedhome.tv.ui.theme.AppIcons
import uk.ahmedhome.tv.ui.theme.Dimens
import uk.ahmedhome.tv.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** null category = all channels; FAVOURITES = favourite channels only. */
const val FAVOURITES = "\u0000favourites"

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(private val c: AppContainer, private val s: Session) : ViewModel() {
    val category = MutableStateFlow<String?>(null)
    val categories = c.library.catalog.categories(s.accountId, "live").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val channels = category.flatMapLatest { cat ->
        c.library.channels(s.accountId, cat.takeIf { it != FAVOURITES }, cat == FAVOURITES)
    }.cachedIn(viewModelScope)
    val favouriteKeys = c.library.user.favouriteChannelKeys(s.accountId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val count = category.flatMapLatest { cat -> if (cat == FAVOURITES) flowOf(-1) else c.library.catalog.channelCount(s.accountId, cat) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val sync = c.sync.progress
    var lastFocusedId: Long? = null

    suspend fun epg(streamId: Long): List<XEpgEntry>? = withContext(Dispatchers.IO) { runCatching { s.client.shortEpg(streamId, 2) }.getOrNull() }

    suspend fun toggleFavourite(ch: ChannelEntity, isFav: Boolean) = withContext(Dispatchers.IO) {
        c.library.toggleFavourite(s.accountId, "live", ch.streamId.toString(), ch.name, ch.icon, isFav)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen() {
    val nav = LocalNav.current
    val app = androidx.compose.ui.platform.LocalContext.current.container
    val vm: LiveViewModel = viewModel(key = "live-${nav.session.accountId}") { LiveViewModel(app, nav.session) }
    val category by vm.category.collectAsState()
    val categories by vm.categories.collectAsState()
    val channels = vm.channels.collectAsLazyPagingItems()
    val favKeys by vm.favouriteKeys.collectAsState()
    val count by vm.count.collectAsState()
    val sync by vm.sync.collectAsState()
    var focused by remember { mutableStateOf<ChannelEntity?>(null) }
    val listState = rememberLazyListState()
    val restoreRequester = remember { FocusRequester() }
    var restoreId by remember { mutableStateOf(vm.lastFocusedId) }

    LaunchedEffect(restoreId, channels.itemCount) {
        if (restoreId != null && channels.itemCount > 0) { delay(50); runCatching { restoreRequester.requestFocus() }; restoreId = null }
    }

    Row(Modifier.fillMaxSize().padding(top = Dimens.safeV, end = Dimens.safeH, start = Dimens.gap)) {
        // Category column
        Column(Modifier.width(168.dp).fillMaxHeight()) {
            PageHeading("Live TV", detail = when {
                category == FAVOURITES -> "Favourite channels"
                count > 0 -> "$count channels"
                sync.running -> "Loading channels…"
                else -> null
            })
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Dimens.safeV)) {
                item(key = "all") { CategoryItem("All channels", category == null) { vm.category.value = null } }
                item(key = "fav") { CategoryItem("Favourites", category == FAVOURITES) { vm.category.value = FAVOURITES } }
                items(categories, key = { it.categoryId }) { cat -> CategoryItem(cat.name, category == cat.categoryId) { vm.category.value = cat.categoryId } }
            }
        }
        Spacer(Modifier.width(16.dp))
        // Channel list
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (channels.itemCount == 0) {
                StatusMessage(
                    title = when { category == FAVOURITES -> "No favourite channels yet"; sync.running -> "Loading channels…"; else -> "No channels here" },
                    body = when { category == FAVOURITES -> "Highlight a channel and choose Add to favourites."; sync.running -> "Your provider's channel list is being saved to this TV."; else -> "Try another category, or refresh the library in Settings." },
                )
            } else {
                LazyColumn(Modifier.focusRestorer(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 70.dp, bottom = Dimens.safeV)) {
                    items(channels.itemCount, key = channels.itemKey { it.streamId }) { i ->
                        val ch = channels[i] ?: return@items
                        val mod = if (ch.streamId == restoreId) Modifier.focusRequester(restoreRequester) else Modifier
                        ChannelRow(ch, isFavourite = ch.streamId.toString() in favKeys, modifier = mod,
                            onFocus = { focused = ch; vm.lastFocusedId = ch.streamId },
                            onClick = {
                                nav.play(PlaybackRequest(ContentKind.LIVE, ch.streamId, null, ch.name, ch.num?.toString(), ch.icon,
                                    categoryId = category.takeIf { it != FAVOURITES }, favouritesOnly = category == FAVOURITES))
                            })
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        // Selected channel facts: EPG is fetched on demand after focus settles; highlight never opens a stream.
        Column(Modifier.width(200.dp).fillMaxHeight().padding(top = 70.dp)) {
            focused?.let { ch -> ChannelFacts(ch, ch.streamId.toString() in favKeys, vm) }
        }
    }
}

@Composable
private fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { this.selected = selected; contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ink.selected else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) Ink.selectedText else Ink.muted, focusedContainerColor = Ink.selected, focusedContentColor = Ink.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

@Composable
private fun ChannelRow(ch: ChannelEntity, isFavourite: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp).onFocusChanged { if (it.isFocused) onFocus() }
            .semantics { contentDescription = "${ch.num ?: ""} ${ch.name}${if (isFavourite) ", favourite" else ""}. Press OK to watch." },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.surface, focusedContainerColor = Ink.selected, contentColor = Ink.text, focusedContentColor = Ink.text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.num?.toString() ?: "", style = MaterialTheme.typography.labelMedium, color = Ink.muted, modifier = Modifier.width(34.dp), maxLines = 1)
            Box(Modifier.size(48.dp, 36.dp).background(Ink.control, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                if (!ch.icon.isNullOrBlank()) Artwork(ch.icon, "", 48.dp, 36.dp, contentScale = ContentScale.Fit)
                else Text(ch.name.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
            Spacer(Modifier.width(14.dp))
            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isFavourite) androidx.tv.material3.Icon(AppIcons.Star, contentDescription = "Favourite", tint = Ink.sage, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChannelFacts(ch: ChannelEntity, isFavourite: Boolean, vm: LiveViewModel) {
    var epg by remember(ch.streamId) { mutableStateOf<List<XEpgEntry>?>(null) }
    var loading by remember(ch.streamId) { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(ch.streamId) {
        delay(450) // let rapid D-pad movement settle before asking the provider
        epg = vm.epg(ch.streamId); loading = false
    }
    LaunchedEffect(pending) { if (pending) { vm.toggleFavourite(ch, isFavourite); pending = false } }
    Column {
        LiveBadge()
        Spacer(Modifier.height(8.dp))
        Text(ch.name, style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Text("Checking programme guide…", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            epg.isNullOrEmpty() -> Text("Programme information unavailable", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            else -> epg!!.take(2).forEachIndexed { i, e ->
                Text(if (i == 0) "NOW" else "NEXT", style = MaterialTheme.typography.labelSmall, color = if (i == 0) Ink.sage else Ink.muted)
                Text(listOfNotNull(e.startEpochSec?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000)) }, e.title).joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        TvButton(if (isFavourite) "Remove favourite" else "Add to favourites", { pending = true },
            icon = if (isFavourite) AppIcons.Check else AppIcons.Plus, tone = ButtonTone.Secondary)
    }
}
