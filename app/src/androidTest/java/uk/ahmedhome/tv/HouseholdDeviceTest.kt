package uk.ahmedhome.tv

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import uk.ahmedhome.tv.data.discovery.TmdbClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import uk.ahmedhome.tv.core.EditionParser
import uk.ahmedhome.tv.data.db.MovieEntity
import uk.ahmedhome.tv.data.db.SeriesEntity
import uk.ahmedhome.tv.data.db.SyncStateEntity
import uk.ahmedhome.tv.data.discovery.DiscoveryRepository
import uk.ahmedhome.tv.data.xtream.ProviderConfig
import uk.ahmedhome.tv.playback.ContentKind
import uk.ahmedhome.tv.playback.Phase
import uk.ahmedhome.tv.playback.PlaybackController
import uk.ahmedhome.tv.playback.PlaybackRequest
import java.io.File
import java.security.MessageDigest

/** Requires the synthetic provider on host port 18099. Never uses a household account or a real TMDB token. */
@RunWith(AndroidJUnit4::class)
class HouseholdDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun englishSubtitlesFollowForeignAudioAndRespectManualOff() = runBlocking {
        val app = context.container
        val session = app.activate(ProviderConfig("http://10.0.2.2:18099", "demo", "demo", true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: PlaybackController
        var created = false
        try {
            withContext(Dispatchers.Main) {
                player = PlaybackController(context, app.http, scope, null) { _, _ -> }
                created = true
                player.setSubtitleMode("foreign")
                player.open(PlaybackRequest(ContentKind.MOVIE, 1, "mkv", "Synthetic subtitle test"), session.client.movieUrl(1, "mkv"), 0)
            }
            withTimeout(25_000) { player.state.first { it.phase == Phase.PLAYING && it.audioTracks.size >= 2 && it.textTracks.isNotEmpty() } }
            assertTrue(player.state.value.subtitlesOff)
            withContext(Dispatchers.Main) { player.selectAudio(player.state.value.audioTracks.last()) }
            withTimeout(10_000) { player.state.first { !it.subtitlesOff } }
            withContext(Dispatchers.Main) { player.selectText(null) }
            withTimeout(5_000) { player.state.first { it.subtitlesOff } }
            withContext(Dispatchers.Main) { player.pause(); player.play() }
            SystemClock.sleep(500)
            assertTrue(player.state.value.subtitlesOff)
            assertTrue(player.openConnections.value <= 1)
        } finally {
            if (created) withContext(Dispatchers.Main) { player.release() }
            scope.cancel()
        }
    }

    @Test fun discoveryHomeAndSettingsUseRealRoomMatchesAndCaptureNativeEvidence() = runBlocking {
        assertTrue("Use a valid TV viewport, not a density-only zoom", context.resources.configuration.screenWidthDp >= 800)
        if (InstrumentationRegistry.getArguments().getString("reviewVariant") == "large-text")
            assertEquals("Capture real enlarged text", 1.3f, context.resources.configuration.fontScale, 0.01f)
        val original = context.container
        original.sync.cancel(); original.discovery.cancel()
        val emptyResponses = AtomicBoolean(false)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (emptyResponses.get())
                    MockResponse().setBody("{\"results\":[]}") else MockResponse().setResponseCode(503)
            }
            start()
        }
        val app = AppContainer(context) { http, value -> TmdbClient(http, value, server.url("/3/")) }
        (context.applicationContext as AhmedHomeApp).container = app
        val session = app.activate(ProviderConfig("http://10.0.2.2:18099", "demo", "demo", true))
        val preferences = context.getSharedPreferences("account", 0)
        val previousToken = preferences.getString("discovery_blob", null)
        val token = "synthetic-device-discovery-token"
        val now = System.currentTimeMillis()
        val feedRows = DiscoveryRepository.LABELS.entries.mapIndexed { index, (id, _) ->
            val language = when { id.startsWith("korean") -> "ko"; id.startsWith("chinese") -> "zh"; else -> "en" }
            val movieId = 810001L + index; val seriesId = 820001L + index
            val tmdbMovie = 910001L + index; val tmdbSeries = 920001L + index
            val film = listOf("The Last Whistle", "Home by Sunday", "Seoul After Rain", "Moonlit Promise", "Lantern Street", "A Spring Together")[index]
            val show = listOf("Matchday Stories", "The Long Weekend", "A Letter from Seoul", "Spring in Busan", "Beyond the Lanterns", "The Quiet City")[index]
            val poster = "http://10.0.2.2:18099/art/poster/$movieId.png"
            app.library.catalog.insertMovieBatch(session.accountId, listOf(MovieEntity(session.accountId, movieId, film, film, film.lowercase(), "10", poster,
                null, now, "mp4", "$tmdbMovie", EditionParser.groupKey("movie", "$tmdbMovie", movieId), "EN", "1080p", 2026, generation = now)))
            app.library.catalog.insertSeriesBatch(session.accountId, listOf(SeriesEntity(session.accountId, seriesId, show, show, show.lowercase(), "20", poster,
                "Synthetic test series", "Drama", null, now, "$tmdbSeries", EditionParser.groupKey("series", "$tmdbSeries", seriesId), null, null, 2026, generation = now)))
            fun title(tid: Long, kind: String, title: String) = mapOf("id" to tid, "kind" to kind, "title" to title, "originalTitle" to title,
                "language" to language, "releaseDate" to "2026-09-01", "poster" to null, "overview" to "Synthetic test catalogue · household discovery preview.")
            mapOf("id" to id, "updatedAt" to now, "titles" to listOf(title(tmdbMovie, "movie", film), title(tmdbSeries, "tv", show)))
        }
        listOf("live", "movie", "series").forEach { app.library.catalog.upsertSyncState(SyncStateEntity(session.accountId, it, now, now, 6, "complete", null)) }
        val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "discovery").apply { mkdirs() }
        val allTitles = feedRows.flatMap { @Suppress("UNCHECKED_CAST") (it["titles"] as List<Map<String, Any?>>) }
        val rows = feedRows.map { row -> when (row["id"]) {
            "trending-movies" -> row + ("titles" to allTitles.filter { it["kind"] == "movie" })
            "trending-series" -> row + ("titles" to allTitles.filter { it["kind"] == "tv" })
            else -> row
        } }
        val adapter = Moshi.Builder().build().adapter(Map::class.java)
        File(directory, "$key.json").writeText(adapter.toJson(mapOf("version" to 1, "feeds" to rows)))
        var activity: android.app.Activity? = null
        try {
            app.accountStore.saveDiscoveryToken(token)
            app.discovery.refresh()
            // A fresh cache avoids any external API call: all evidence uses synthetic metadata.
            withTimeout(10_000) { app.discovery.state.first { it.feeds.size == 6 } }
            app.discovery.cancel()
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { focusedTag()?.startsWith("feed-card-") == true }
            settle()
            assertFullyVisible("home-welcome")
            assertFeed("trending-movies"); capture("tv-home-1080.png")
            listOf("trending-series", "korean-trending", "korean-new", "chinese-trending", "chinese-new").forEach { id ->
                focusFeed(id); capture("tv-$id-1080.png")
            }
            click("Settings")
            scrollTo("English for foreign audio")
            assertFullyVisible("settings-playback-heading"); assertFullyVisible("settings-subtitles")
            capture("tv-playback-settings-1080.png")
            scrollTo("Save discovery token")
            assertFullyVisible("settings-discovery-heading")
            capture("tv-discovery-settings-1080.png")
            compose.onNode(hasContentDescription("TMDB API Read Access Token"), useUnmergedTree = true).assertIsDisplayed()
            assertTrue(app.discovery.state.value.feeds.size == 6)
            scrollTo("Show credits"); click("Show credits")
            waitFor("This product uses the TMDB API but is not endorsed or certified by TMDB.")
            assertFullyVisible("tmdb-disclosure"); assertFullyVisible("tmdb-logo")
            capture("tv-credits-1080.png")

            // Exercise actual repository failure/cache and empty/unconfigured UI against a local server.
            val staleToken = token + "-stale"
            val staleKey = MessageDigest.getInstance("SHA-256").digest(staleToken.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            val staleRows = rows.map { it + ("updatedAt" to now - DiscoveryRepository.REFRESH_INTERVAL - 1_000) }
            File(directory, "$staleKey.json").writeText(adapter.toJson(mapOf("version" to 1, "feeds" to staleRows)))
            app.discovery.cancel(); app.accountStore.saveDiscoveryToken(staleToken); app.discovery.refresh()
            withTimeout(10_000) { app.discovery.state.first { it.error != null && !it.loading } }
            click("Home"); settle()
            focusFeed("trending-movies"); capture("tv-cached-stale-1080.png")
            scrollTo("Retry discovery"); capture("tv-refresh-failure-1080.png")
            emptyResponses.set(true)
            app.discovery.refresh(force = true)
            withTimeout(10_000) { app.discovery.state.first { !it.loading && it.error == null && it.feeds.size == 6 && it.feeds.all { feed -> feed.titles.isEmpty() } } }
            click("Settings"); scrollTo("Save discovery token"); click("Home"); settle()
            assertFullyVisible("discovery-empty")
            capture("tv-no-matches-1080.png")
            click("Settings"); scrollTo("Remove token"); click("Remove token")
            withTimeout(5_000) { app.discovery.state.first { !it.configured } }
            click("Home"); scrollTo("Set up discovery"); assertFullyVisible("discovery-unconfigured"); capture("tv-unconfigured-1080.png")
        } finally {
            app.discovery.cancel(); app.sync.cancel()
            instrumentation.runOnMainSync { activity?.finish() }
            if (previousToken == null) preferences.edit().remove("discovery_blob").commit()
            else preferences.edit().putString("discovery_blob", previousToken).commit()
            (context.applicationContext as AhmedHomeApp).container = original
            app.appScope.cancel(); app.database.close(); server.shutdown()
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(8_000) { compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        settle()
    }

    private fun focusedLabels(): List<String> = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun click(label: String) {
        val rail = listOf("Search", "Home", "Live TV", "Movies", "Series", "My List", "Settings")
        if (label in rail) {
            // Back enters the selected navigation item; the rail's focus restorer intentionally
            // prevents jumping straight from content to a different destination's focus target.
            if (focusedLabels().none { it in rail }) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle()
            }
            repeat(rail.size) {
                val current = focusedLabels().firstOrNull { it in rail } ?: error("Expected navigation focus: ${focusedLabels()}")
                if (current != label) {
                    instrumentation.sendKeyDownUpSync(if (rail.indexOf(current) < rail.indexOf(label)) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                    settle()
                }
            }
        }
        assertTrue("Remote selects $label, got ${focusedLabels()}", label in focusedLabels())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        if (label in rail && focusedLabels().any { it in rail }) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
        }
    }

    private fun settle() {
        compose.waitForIdle()
        SystemClock.sleep(600)
        compose.waitForIdle()
    }

    private fun focusedTag(): String? {
        val focus = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull() ?: return null
        return generateSequence(focus) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.firstOrNull()
    }

    private fun geometry(tag: String): String = runCatching {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        "${node.layoutInfo.coordinates.positionInWindow()}/${node.layoutInfo.coordinates.size}"
    }.getOrElse { "absent" }

    private fun assertFullyVisible(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        var viewport = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        generateSequence(node.parent) { it.parent }.filter { ancestor ->
            ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        }.forEach { viewport = viewport.intersect(it.boundsInRoot) }
        val position = node.layoutInfo.coordinates.positionInWindow()
        val size = node.layoutInfo.coordinates.size
        assertTrue("$tag fully framed: ${geometry(tag)}, viewport=$viewport", position.y >= viewport.top &&
            position.y + size.height <= viewport.bottom && position.x >= viewport.left && position.x + size.width <= viewport.right)
    }

    private fun assertFeed(id: String) {
        assertTrue("Focused feed $id, got ${focusedTag()}", focusedTag()?.startsWith("feed-card-$id-") == true)
        assertFullyVisible("feed-heading-$id")
        assertFullyVisible(focusedTag()!!)
    }

    private fun focusFeed(id: String) {
        val trace = mutableListOf<String>()
        repeat(10) {
            settle()
            trace += "focus=${focusedTag()} heading=${geometry("feed-heading-$id")}"
            if (focusedTag()?.startsWith("feed-card-$id-") == true) {
                try { assertFeed(id); return } catch (error: AssertionError) {
                    capture("tv-navigation-failure-1080.png")
                    throw AssertionError("$trace", error)
                }
            }
            val order = DiscoveryRepository.LABELS.keys.toList()
            val current = order.indexOfFirst { focusedTag()?.startsWith("feed-card-$it-") == true }
            instrumentation.sendKeyDownUpSync(if (current > order.indexOf(id)) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        }
        capture("tv-navigation-failure-1080.png")
        error("Could not focus $id: $trace")
    }

    private fun scrollTo(label: String) {
        val trace = mutableListOf<String>()
        repeat(16) {
            settle()
            val matches = compose.onAllNodes(androidx.compose.ui.test.hasText(label) or androidx.compose.ui.test.hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes()
            val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            fun ancestors(node: SemanticsNode) = generateSequence(node) { it.parent }.toList()
            trace += focused.map { f -> "focus ${f.id}: " + ancestors(f).take(3).map { n ->
                "${n.id}/${n.config.getOrNull(SemanticsProperties.Text)}/${n.config.getOrNull(SemanticsProperties.ContentDescription)} edit=${n.config.getOrNull(SemanticsProperties.EditableText) != null}"
            } }.toString() + " matches=" + matches.map { it.id }
            if (focused.any { focus -> matches.any { match -> ancestors(match).any { it.id == focus.id } || ancestors(focus).any { it.id == match.id } } }) return
            val editable = focused.any { f -> ancestors(f).any { it.config.getOrNull(SemanticsProperties.EditableText) != null } }
            val f = focused.firstOrNull()?.boundsInRoot
            val sameRow = matches.firstOrNull { candidate ->
                val b = candidate.boundsInRoot
                f != null && b.width > 0 && b.height > 0 && kotlin.math.abs(b.center.y - f.center.y) < minOf(b.height, f.height) / 2
            }?.boundsInRoot
            fun settingRow(value: String?): Int = when (value) {
                "Refresh library", "Refreshing…" -> 0
                "MPEG-TS", "HLS" -> 1
                "Automatic", "EN", "AR", "FR", "UR", "HI", "TR", "DE", "ES" -> 2
                "English for foreign audio", "Always English", "Off" -> 3
                "TMDB API Read Access Token" -> 4
                "Save discovery token", "Remove token" -> 5
                "Refresh discovery" -> 6
                "Sign out" -> 7
                "Clear artwork cache" -> 8
                "Show credits", "Hide credits" -> 9
                else -> -1
            }
            val currentRow = settingRow(focusedLabels().firstOrNull())
            val targetRow = settingRow(label)
            val key = when {
                editable -> KeyEvent.KEYCODE_ENTER
                sameRow != null && f != null && sameRow.center.x < f.center.x -> KeyEvent.KEYCODE_DPAD_LEFT
                sameRow != null && f != null && sameRow.center.x > f.center.x -> KeyEvent.KEYCODE_DPAD_RIGHT
                currentRow >= 0 && targetRow >= 0 && currentRow > targetRow -> KeyEvent.KEYCODE_DPAD_UP
                else -> KeyEvent.KEYCODE_DPAD_DOWN
            }
            instrumentation.sendKeyDownUpSync(key)
        }
        capture("tv-navigation-failure-1080.png")
        error("Could not focus $label; $trace")
    }

    private fun capture(filename: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No native screenshot")
        // Shared test images survive Gradle's test-APK uninstall; only synthetic data is captured.
        val values = ContentValues().apply {
            val variant = InstrumentationRegistry.getArguments().getString("reviewVariant")?.let { "$it-" }.orEmpty()
            put(MediaStore.Images.Media.DISPLAY_NAME, variant + filename.replace("1080", "${bitmap.width}x${bitmap.height}"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ahmedhome-tv-review/${bitmap.width}/")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No capture destination")
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
