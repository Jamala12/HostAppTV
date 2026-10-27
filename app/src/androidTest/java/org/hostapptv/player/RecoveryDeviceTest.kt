package org.hostapptv.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.hostapptv.player.data.db.SyncStateEntity
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.playback.*
import org.hostapptv.player.ui.shell.MainViewModel
import org.hostapptv.player.ui.shell.Route
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android player, socket cancellation and D-pad checks using only a local synthetic provider. */
@RunWith(AndroidJUnit4::class)
class RecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    @Test fun temporaryProviderErrorsRecoverWithinTheSameBudget() = withTestApp { app, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://10.0.2.2:18099/live/demo/demo/1.ts"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: PlaybackController? = null
        try {
            withContext(Dispatchers.Main) {
                controller = PlaybackController(context, app.http, scope, null) { _, _ -> }.also {
                    it.open(PlaybackRequest(ContentKind.LIVE, 1, "ts", "Synthetic recovering channel"), server.url("/live/demo/demo/1.ts").toString(), 0)
                }
            }
            withTimeout(15_000) { controller!!.state.first { it.phase == Phase.PLAYING } }
            assertTrue(server.requestCount >= 3)
            assertTrue(controller!!.openConnections.value <= 1)
        } finally {
            withContext(Dispatchers.Main) { controller?.release() }
            scope.cancel()
        }
    }

    @Test fun droppedProgressiveLiveReconnectsToAFreshBroadcast() = withTestApp { app, _ ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: PlaybackController? = null
        try {
            withContext(Dispatchers.Main) {
                controller = PlaybackController(context, app.http, scope, null) { _, _ -> }.also {
                    it.open(PlaybackRequest(ContentKind.LIVE, 9004, "ts", "Synthetic dropped channel"), "http://10.0.2.2:18099/live/demo/demo/9004.ts", 0)
                }
            }
            withTimeout(10_000) { controller!!.state.first { it.phase == Phase.PLAYING } }
            withTimeout(20_000) { controller!!.state.first { it.phase == Phase.RETRYING } }
            withTimeout(10_000) { controller!!.state.first { it.phase == Phase.PLAYING } }
            assertTrue("Reconnect should start a fresh live timeline", controller!!.state.value.positionMs < 5_000)
            assertTrue(controller!!.openConnections.value <= 1)
        } finally {
            withContext(Dispatchers.Main) { controller?.release() }
            scope.cancel()
        }
    }

    @Test fun pendingConnectionsCannotExtendRecoveryPastFifteenSeconds() = withTestApp { app, server ->
        repeat(10) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: PlaybackController
        var created = false
        try {
            val started = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) {
                controller = PlaybackController(context, app.http, scope, null) { _, _ -> }
                created = true
                controller.open(PlaybackRequest(ContentKind.LIVE, 1, "ts", "Synthetic silent channel"), server.url("/live/demo/demo/1.ts").toString(), 0)
            }
            withTimeout(18_000) { controller.state.first { it.phase == Phase.ERROR } }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Recovery took ${elapsed}ms", elapsed in 14_500..17_000)
            assertTrue(controller.state.value.retryAttempt >= 1)
            assertEquals(FailureClass.TRANSIENT, controller.state.value.failure)
            withTimeout(3_000) { controller.openConnections.first { it == 0 } }
        } finally {
            if (created) withContext(Dispatchers.Main) { controller.release() }
            scope.cancel()
        }
    }

    @Test fun backLeavesImmediatelyWhileAConnectionIsPending() = withTestApp { app, server ->
        repeat(10) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        var activity: Activity? = null
        try {
            activity = inst.startActivitySync(PlaybackRequest(ContentKind.LIVE, 1, "ts", "Synthetic silent channel")
                .toIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Leave") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            val field = PlayerActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            lateinit var controller: PlaybackController
            inst.runOnMainSync { controller = field.get(activity) as PlaybackController }
            val started = SystemClock.elapsedRealtime()
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(3_000) { activity?.isFinishing == true }
            withTimeout(3_000) { controller.state.first { it.phase == Phase.SUSPENDED } }
            assertTrue(SystemClock.elapsedRealtime() - started < 3_000)
            withTimeout(3_000) { controller.openConnections.first { it == 0 } }
        } finally { activity?.let { a -> inst.runOnMainSync { a.finish() } } }
    }

    @Test fun libraryStatusCanBeReachedAboveRefreshWithTheRemote() = withTestApp { app, server ->
        server.enqueue(MockResponse().setBody("{}"))
        val session = app.session.value!!
        val now = System.currentTimeMillis()
        listOf("live", "movie", "series").forEach { kind ->
            app.library.catalog.upsertSyncState(SyncStateEntity(session.accountId, kind, now, now, 0, "complete", null))
        }
        var activity: Activity? = null
        try {
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Home")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle(); SystemClock.sleep(300)
            listOf("live", "movie", "series").forEach { kind ->
                app.library.catalog.upsertSyncState(SyncStateEntity(session.accountId, kind, now, now, 0, "failed", "Network unavailable or provider unreachable"))
            }
            inst.runOnMainSync { ViewModelProvider(activity as MainActivity)[MainViewModel::class.java].navigateTop(Route.Settings) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Refresh library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle(); SystemClock.sleep(300)
            compose.onNodeWithTag("settings-library-live").assertIsFocused()
            repeat(3) { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle(); SystemClock.sleep(300) }
            compose.onNode(hasText("Refresh library") and hasClickAction()).assertIsFocused()
            repeat(3) { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); compose.waitForIdle(); SystemClock.sleep(300) }
            compose.onNodeWithTag("settings-library-live").assertIsFocused()
            val row = compose.onNodeWithTag("settings-library-live").getUnclippedBoundsInRoot()
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Live TV status is clipped: $row in $root", row.top >= root.top && row.bottom <= root.bottom)
            val heading = compose.onNodeWithTag("settings-library-heading").getUnclippedBoundsInRoot()
            assertTrue("Library heading is clipped", heading.top >= root.top && heading.bottom <= row.top)
            File(context.filesDir, "settings-library-status.png").outputStream().use {
                inst.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { activity?.let { a -> inst.runOnMainSync { a.finish() } } }
    }

    private fun withTestApp(block: suspend (AppContainer, MockWebServer) -> Unit) = runBlocking {
        val application = context.applicationContext as HostAppTVApplication
        inst.setInTouchMode(false)
        val original = application.container
        original.sync.cancel(); original.discovery.cancel()
        val server = MockWebServer().apply { start() }
        val app = AppContainer(context)
        application.container = app
        app.activate(ProviderConfig(server.url("/").toString(), "demo", "demo", true))
        try { block(app, server) }
        finally {
            app.sync.cancel(); app.discovery.cancel(); app.appScope.cancel()
            application.container = original
            server.shutdown()
        }
    }
}
