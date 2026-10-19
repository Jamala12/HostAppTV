package uk.ahmedhome.tv.ui.settings

import android.media.MediaCodecList
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.ahmedhome.tv.BuildConfig
import uk.ahmedhome.tv.container
import uk.ahmedhome.tv.data.xtream.AccountInfo
import uk.ahmedhome.tv.ui.components.ButtonTone
import uk.ahmedhome.tv.ui.components.PageHeading
import uk.ahmedhome.tv.ui.components.SectionTitle
import uk.ahmedhome.tv.ui.components.TvButton
import uk.ahmedhome.tv.ui.shell.LocalNav
import uk.ahmedhome.tv.ui.theme.AppIcons
import uk.ahmedhome.tv.ui.theme.Dimens
import uk.ahmedhome.tv.ui.theme.Ink
import java.text.DateFormat
import java.util.Date

private val languages = listOf(null, "EN", "AR", "FR", "UR", "HI", "TR", "DE", "ES")

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
    val decoders = remember { decoderSummary() }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        runCatching { withContext(Dispatchers.IO) { nav.session.client.authenticate() } }
            .onSuccess { account = it }.onFailure { accountError = it.message }
    }
    LaunchedEffect(confirmSignOut) { if (confirmSignOut) { delay(6_000); confirmSignOut = false } }

    Column(Modifier.fillMaxSize()) {
    PageHeading("Settings", Modifier.padding(start = Dimens.gap, top = Dimens.safeV))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Dimens.gap, end = Dimens.safeH, top = 4.dp, bottom = Dimens.safeV),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {

        item {
            SectionTitle("Library")
            listOf("live" to "Live TV", "movie" to "Movies", "series" to "Series").forEach { (k, label) ->
                val st = states.firstOrNull { it.kind == k }
                Fact(label, when {
                    sync.running && sync.current?.key == k -> "Refreshing… ${sync.itemsInCurrent} saved so far"
                    st == null -> "Not loaded yet"
                    st.status == "failed" -> "Last refresh failed${st.error?.let { ": $it" } ?: ""}" + (st.lastCompleteAt?.let { " · library from ${time(it)} still in use" } ?: "")
                    st.lastCompleteAt != null -> "${st.count} provider entries · updated ${time(st.lastCompleteAt)}"
                    else -> st.status
                })
            }
            Spacer(Modifier.height(10.dp))
            TvButton(if (sync.running) "Refreshing…" else "Refresh library", onRefresh, Modifier.focusRequester(first), icon = AppIcons.Refresh, enabled = !sync.running)
            Text("Refreshing replaces the saved catalogue only after each part downloads completely. Favourites and progress are kept.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
        }

        item {
            SectionTitle("Playback")
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
        }

        item {
            SectionTitle("Account")
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

        item {
            SectionTitle("Storage")
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

        item {
            SectionTitle("This device")
            Fact("Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            Fact("System", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            Fact("App ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
            decoders.forEach { (mime, d) -> Fact(mime, d) }
        }

        item {
            SectionTitle("About")
            Fact("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text(
                "Ahmed Home TV connects only to the provider you entered. Playback uses AndroidX Media3. Headings use Gelasio (SIL Open Font License 1.1). Provider artwork and metadata belong to their owners.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
        }
    }
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
