package uk.ahmedhome.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import uk.ahmedhome.tv.Session
import uk.ahmedhome.tv.container
import uk.ahmedhome.tv.playback.PlaybackRequest
import uk.ahmedhome.tv.ui.catalog.CatalogKindUi
import uk.ahmedhome.tv.ui.catalog.CatalogScreen
import uk.ahmedhome.tv.ui.components.BrandLockup
import uk.ahmedhome.tv.ui.details.MovieDetailScreen
import uk.ahmedhome.tv.ui.details.SeriesDetailScreen
import uk.ahmedhome.tv.ui.home.HomeScreen
import uk.ahmedhome.tv.ui.live.LiveScreen
import uk.ahmedhome.tv.ui.mylist.MyListScreen
import uk.ahmedhome.tv.ui.search.SearchScreen
import uk.ahmedhome.tv.ui.settings.SettingsScreen
import uk.ahmedhome.tv.ui.setup.SetupScreen
import uk.ahmedhome.tv.ui.theme.AppIcons
import uk.ahmedhome.tv.ui.theme.Dimens
import uk.ahmedhome.tv.ui.theme.Ink

/** Actions every screen may use, without passing the view model around. */
class Nav(
    val session: Session,
    val push: (Route) -> Unit,
    val goTop: (Route) -> Unit,
    val play: (PlaybackRequest) -> Unit,
    val focusRail: () -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

@Composable
fun AppRoot(vm: MainViewModel) {
    when (val s = vm.state.collectAsState().value) {
        AppState.Starting -> Box(Modifier.fillMaxSize().background(Ink.canvas))
        is AppState.NeedsSetup -> SetupScreen(vm)
        is AppState.Ready -> Shell(vm, s.session)
    }
}

private data class Dest(val route: Route, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest(Route.Search, "Search", AppIcons.Search),
    Dest(Route.Home, "Home", AppIcons.Home),
    Dest(Route.Live, "Live TV", AppIcons.Live),
    Dest(Route.Movies, "Movies", AppIcons.Movie),
    Dest(Route.Series, "Series", AppIcons.Series),
    Dest(Route.MyList, "My List", AppIcons.MyList),
)
private val settingsDest = Dest(Route.Settings, "Settings", AppIcons.Settings)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shell(vm: MainViewModel, session: Session) {
    val context = LocalContext.current
    val current = vm.stack.lastOrNull() ?: Route.Home
    val topLevel = vm.stack.firstOrNull() ?: Route.Home
    val railRequesters = remember { (destinations + settingsDest).associate { it.route to FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()

    val nav = remember(session) {
        Nav(
            session = session,
            push = { vm.push(it) },
            goTop = { vm.navigateTop(it) },
            play = { req -> context.startActivity(req.toIntent(context)) },
            focusRail = { runCatching { railRequesters[vm.stack.firstOrNull() ?: Route.Home]?.requestFocus() } },
        )
    }

    // Back: details pop; top-level content goes to its rail item; on the rail, Android handles exit.
    BackHandler(enabled = vm.stack.size > 1 || !railHasFocus) {
        if (!vm.pop()) nav.focusRail()
    }

    // Cold launch: focus lands on a real control immediately (Home moves it into content when it has an action).
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); if (!railHasFocus) nav.focusRail() }

    CompositionLocalProvider(LocalNav provides nav) {
        Row(Modifier.fillMaxSize().background(Ink.canvas)) {
            if (current.isTopLevel) {
                Column(
                    Modifier.width(Dimens.railWidth + Dimens.safeH).fillMaxHeight().background(Ink.surface)
                        .padding(start = Dimens.safeH - 16.dp, end = 12.dp, top = Dimens.safeV, bottom = Dimens.safeV)
                        .onFocusChanged { railHasFocus = it.hasFocus }
                        // Entering the rail lands on the selected destination (restorer must wrap the group).
                        .focusRestorer(railRequesters[topLevel] ?: FocusRequester.Default)
                        .focusGroup(),
                ) {
                    BrandLockup(Modifier.padding(start = 12.dp, bottom = 26.dp), compact = true)
                    destinations.forEach { d ->
                        RailItem(d, selected = topLevel == d.route, requester = railRequesters.getValue(d.route)) { vm.navigateTop(d.route) }
                    }
                    Spacer(Modifier.weight(1f))
                    RailItem(settingsDest, selected = topLevel == Route.Settings, requester = railRequesters.getValue(Route.Settings)) { vm.navigateTop(Route.Settings) }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusGroup(),
            ) {
                holder.SaveableStateProvider(current.key) {
                    when (current) {
                        Route.Search -> SearchScreen()
                        Route.Home -> HomeScreen()
                        Route.Live -> LiveScreen()
                        Route.Movies -> CatalogScreen(CatalogKindUi.MOVIES)
                        Route.Series -> CatalogScreen(CatalogKindUi.SERIES)
                        Route.MyList -> MyListScreen()
                        Route.Settings -> SettingsScreen(onSignOut = vm::signOut, onRefresh = vm::refreshLibrary)
                        is Route.MovieDetail -> MovieDetailScreen(current)
                        is Route.SeriesDetail -> SeriesDetailScreen(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(d: Dest, selected: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = d.label },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ink.selected else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) Ink.selectedText else Ink.muted,
            focusedContainerColor = Ink.selected, focusedContentColor = Ink.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(d.icon, null, Modifier.size(21.dp))
            Spacer(Modifier.width(13.dp))
            Text(d.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (selected && !focused) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(5.dp).background(Ink.sage, RoundedCornerShape(50)))
            }
        }
    }
}

