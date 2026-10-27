package org.hostapptv.player.ui.settings

import android.media.MediaCodecList
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.BuildConfig
import org.hostapptv.player.R
import org.hostapptv.player.container
import org.hostapptv.player.data.xtream.AccountInfo
import org.hostapptv.player.data.discovery.TmdbClient
import org.hostapptv.player.data.discovery.DiscoveryException
import org.hostapptv.player.playback.SubtitlePolicy
import org.hostapptv.player.ui.setup.Field
import org.hostapptv.player.ui.components.TvFocusScrollSpec
import org.hostapptv.player.ui.components.ButtonTone
import org.hostapptv.player.ui.components.PageHeading
import org.hostapptv.player.ui.components.SectionTitle
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.shell.LocalNav
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink
import java.text.DateFormat
import java.util.Date

private val languages = listOf(null, "EN", "AR", "FR", "UR", "HI", "TR", "DE", "ES")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(onSignOut: () -> Unit, onRefresh: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(null)
    val sync by app.sync.progress.collectAsState()
    val states by remember { app.library.catalog.syncStates(nav.session.accountId) }.collectAsState(emptyList())
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var discoveryToken by remember { mutableStateOf("") }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }
    var savingDiscovery by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    val discovery by app.discovery.state.collectAsState()
    val decoders = remember { decoderSummary() }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // LazyColumn must attach the first status row before it can take remote focus.
        delay(32)
        runCatching { first.requestFocus() }
        runCatching { withContext(Dispatchers.IO) { nav.session.client.authenticate() } }
            .onSuccess { account = it }.onFailure { accountError = it.message }
    }
    LaunchedEffect(confirmSignOut) { if (confirmSignOut) { delay(6_000); confirmSignOut = false } }

    Column(Modifier.fillMaxSize()) {
    PageHeading("Settings", Modifier.padding(start = Dimens.gap, top = Dimens.safeV))
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvFocusScrollSpec) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Dimens.gap, end = Dimens.safeH, top = 4.dp, bottom = Dimens.safeV),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {

        stickyHeader { SectionTitle("Library", Modifier.background(Ink.canvas).testTag("settings-library-heading")) }
        item {
            listOf("live" to "Live TV", "movie" to "Movies", "series" to "Series").forEach { (k, label) ->
                val st = states.firstOrNull { it.kind == k }
                val status = when {
                    sync.running && sync.current?.key == k -> "Refreshing… ${sync.itemsInCurrent} saved so far"
                    st == null -> "Not loaded yet"
                    st.status == "failed" -> "Last refresh failed${st.error?.let { ": $it" } ?: ""}" + (st.lastCompleteAt?.let { " · library from ${time(it)} still in use" } ?: "")
                    st.lastCompleteAt != null -> "${st.count} provider entries · updated ${time(st.lastCompleteAt)}"
                    else -> st.status
                }
                LibraryStatus(label, status, Modifier.testTag("settings-library-$k")
                    .then(if (k == "live") Modifier.focusRequester(first) else Modifier))
            }
            Spacer(Modifier.height(10.dp))
            TvButton(if (sync.running) "Refreshing…" else "Refresh library", { if (!sync.running) onRefresh() }, icon = AppIcons.Refresh)
            Text("Refreshing replaces the saved catalogue only after each part downloads completely. Favourites and progress are kept.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
            Text("Bulk refresh pauses while you watch and when you leave the app, then resumes when you return. Already-saved content stays usable.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
        }

        stickyHeader { SectionTitle("Playback", Modifier.background(Ink.canvas).testTag("settings-playback-heading")) }
        item {
            val s = settings
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Live stream format", style = MaterialTheme.typography.bodyMedium, color = Ink.text, modifier = Modifier.width(220.dp))
                listOf("ts" to "MPEG-TS", "m3u8" to "HLS").forEach { (v, l) ->
                    TvButton(l, { scope.launch { app.settings.update { it.copy(liveFormat = v) } } }, selected = s?.liveFormat == v, icon = if (s?.liveFormat == v) AppIcons.Check else null)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Preferred edition language", style = MaterialTheme.typography.bodyMedium, color = Ink.text, modifier = Modifier.width(220.dp))
                TvButton(s?.preferredLanguage ?: "Automatic", {
                    val next = languages[(languages.indexOf(s?.preferredLanguage) + 1) % languages.size]
                    scope.launch { app.settings.update { it.copy(preferredLanguage = next) } }
                })
            }
            Text("Used to pick the default edition on title pages. Every edition remains selectable.", style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitles", style = MaterialTheme.typography.bodyMedium, color = Ink.text, modifier = Modifier.width(220.dp))
                TvButton(SubtitlePolicy.label(s?.subtitleMode ?: "foreign"), {
                    val next = SubtitlePolicy.modes[(SubtitlePolicy.modes.indexOf(s?.subtitleMode ?: "foreign") + 1) % SubtitlePolicy.modes.size]
                    scope.launch { app.settings.update { it.copy(subtitleMode = next) } }
                }, Modifier.testTag("settings-subtitles"))
            }
            Text("Foreign audio uses English subtitles when the stream supplies them. Original/default audio is retained; audio and subtitles can be changed in the player.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
        }

        stickyHeader { SectionTitle("Discovery", Modifier.background(Ink.canvas).testTag("settings-discovery-heading")) }
        item {
            Text("New and trending films and series from your library, including Korean and Chinese titles. Cached discovery works offline.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            Spacer(Modifier.height(12.dp))
            Field("TMDB API Read Access Token", discoveryToken, { discoveryToken = it.take(4096) }, "", hidden = true,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            Text("Without “Bearer”. Encrypted on this TV; never bundled, backed up or logged.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(if (savingDiscovery) "Checking…" else "Save discovery token", {
                    if (!Regex("[A-Za-z0-9_.-]{20,4096}").matches(discoveryToken.trim())) {
                        discoveryMessage = "Enter the TMDB API Read Access Token, without Bearer."
                    } else {
                        savingDiscovery = true
                        scope.launch {
                            try {
                                val token = discoveryToken.trim()
                                TmdbClient(app.http, token).validateToken()
                                withContext(Dispatchers.IO) { app.accountStore.saveDiscoveryToken(token) }
                                discoveryToken = ""; discoveryMessage = "Discovery token checked and saved on this TV."
                                app.discovery.cancel(); app.discovery.refresh(force = true)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { discoveryMessage = (e as? DiscoveryException)?.reason ?: "Couldn't save discovery. Check the connection and try again." }
                            finally { savingDiscovery = false }
                        }
                    }
                }, enabled = !savingDiscovery, tone = ButtonTone.Primary)
                if (discovery.configured) TvButton("Remove token", {
                    scope.launch {
                        app.discovery.cancel()
                        withContext(Dispatchers.IO) { app.accountStore.clearDiscoveryToken() }
                        discoveryToken = ""; discoveryMessage = "Discovery token removed. Your provider library is unchanged."
                        app.discovery.refresh(force = true)
                    }
                }, enabled = !savingDiscovery)
            }
            Text(discoveryMessage ?: discovery.error ?: when {
                discovery.loading -> "Refreshing discovery…"
                discovery.configured -> "Token saved on this TV."
                else -> "Discovery is not connected yet. Live TV and your library work without it."
            }, style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
            if (discovery.configured) TvButton("Refresh discovery", { app.discovery.refresh(force = true) }, enabled = !discovery.loading)
        }

        stickyHeader { SectionTitle("Account", Modifier.background(Ink.canvas).testTag("settings-account-heading")) }
        item {
            Fact("Server", nav.session.config.baseUrl.substringAfter("://").substringBefore('/'))
            Fact("Connection", if (nav.session.config.isCleartext) "HTTP (unencrypted, allowed by you)" else "HTTPS")
            Fact("Status", account?.let { a -> listOfNotNull(a.status ?: "Active", a.expiresAtEpochSec?.let { "expires ${DateFormat.getDateInstance().format(Date(it * 1000))}" },
                a.maxConnections?.let { "$it connection${if (it == 1) "" else "s"} allowed" }).joinToString(" · ") } ?: accountError?.let { "Couldn't check: $it" } ?: "Checking…")
            Spacer(Modifier.height(10.dp))
            TvButton(if (confirmSignOut) "Press again to sign out" else "Sign out", { if (confirmSignOut) onSignOut() else confirmSignOut = true },
                icon = AppIcons.Back, tone = if (confirmSignOut) ButtonTone.Primary else ButtonTone.Secondary)
            Text("Signing out forgets the saved login on this TV. Favourites and progress stay, ready if you sign in again.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
        }

        stickyHeader { SectionTitle("Storage", Modifier.background(Ink.canvas).testTag("settings-storage-heading")) }
        item {
            TvButton("Clear artwork cache", {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear(); loader.diskCache?.clear()
                    }
                    cacheMessage = "Artwork cache cleared. Posters will download again as you browse."
                }
            }, icon = AppIcons.Refresh)
            Text(cacheMessage ?: "Removes downloaded posters and logos only. Library, favourites and progress are not affected.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
        }

        stickyHeader { SectionTitle("This device", Modifier.background(Ink.canvas).testTag("settings-device-heading")) }
        item {
            Fact("Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            Fact("System", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            Fact("App ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
            decoders.forEach { (mime, d) -> Fact(mime, d) }
        }

        stickyHeader { SectionTitle("About", Modifier.background(Ink.canvas).testTag("settings-about-heading")) }
        item {
            Fact("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text(
                "HostAppTV connects to the provider you entered and, when discovery is configured, TMDB for public metadata and artwork. Playback uses AndroidX Media3. Headings use Gelasio (SIL Open Font License 1.1). Provider artwork and metadata belong to their owners.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
            if (showCredits) {
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp).testTag("tmdb-disclosure"))
                Image(painterResource(R.drawable.tmdb_logo), "The Movie Database (TMDB)", Modifier.padding(top = 12.dp).width(76.dp).height(33.dp).testTag("tmdb-logo"))
            }
            TvButton(if (showCredits) "Hide credits" else "Show credits", { showCredits = !showCredits }, Modifier.padding(top = 12.dp))
        }
    }
    }
    }
}

@Composable
private fun LibraryStatus(label: String, value: String, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(BorderStroke(Dimens.focusBorder, Ink.focus), MaterialTheme.shapes.small) else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = "$label. $value" }
        .focusable()
        .padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.width(212.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ink.text)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.width(220.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ink.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun time(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

/** Hardware video decoders per codec with their largest supported size; helps verify 4K/HEVC on the stick. */
private fun decoderSummary(): List<Pair<String, String>> = runCatching {
    val wanted = listOf("video/avc" to "H.264 hardware", "video/hevc" to "HEVC hardware", "video/x-vnd.on2.vp9" to "VP9 hardware", "video/av01" to "AV1 hardware")
    val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
    wanted.map { (mime, label) ->
        val all = infos.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }
        val hardware = all.filter { it.isHardwareAccelerated }.map { info ->
            val caps = info.getCapabilitiesForType(mime).videoCapabilities
            info.name + (caps?.let { c -> " (up to ${c.supportedWidths.upper}×${c.supportedHeights.upper})" } ?: "")
        }
        label to when {
            hardware.isNotEmpty() -> hardware.joinToString("; ")
            all.isNotEmpty() -> "software only (4K is refused rather than decoded in software)"
            else -> "none"
        }
    }
}.getOrDefault(emptyList())
