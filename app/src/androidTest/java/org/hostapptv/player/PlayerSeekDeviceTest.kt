package org.hostapptv.player

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.playback.ContentKind
import org.hostapptv.player.playback.Phase
import org.hostapptv.player.playback.PlaybackController
import org.hostapptv.player.playback.PlaybackRequest
import org.hostapptv.player.playback.PlayerActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full remote key sequences against synthetic MP4s, including an 18-second range-response delay. */
@RunWith(AndroidJUnit4::class)
class PlayerSeekDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    @Test fun movieSeekWaitsBeyondLiveDeadlineWithoutLeaving() = slowSeek(ContentKind.MOVIE)
    @Test fun episodeSeekWaitsBeyondLiveDeadlineWithoutLeaving() = slowSeek(ContentKind.EPISODE)

    private fun slowSeek(kind: ContentKind) = withPlayer(kind, 9005) { activity, controller ->
        repeat(9) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.onNode(hasText("OK to jump, Back to cancel", substring = true)).assertExists()
        val started = SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(3_000) { controller.state.value.phase == Phase.BUFFERING }
        compose.onNode(hasText("Back to leave")).assertExists()
        compose.onNode(hasText("Leave") and hasClickAction()).assertDoesNotExist()
        assertFalse(activity.isFinishing)
        if (kind == ContentKind.EPISODE) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            compose.onNodeWithTag("player-seek-forward").assertIsFocused()
        }
        SystemClock.sleep((16_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
        assertEquals(Phase.BUFFERING, controller.state.value.phase)
        assertEquals(0, controller.state.value.retryAttempt)
        assertFalse(activity.isFinishing)
        withTimeout(8_000) { controller.state.first { it.phase == Phase.PLAYING } }
        assertTrue(controller.state.value.positionMs >= 89_000)
        assertEquals(0, controller.state.value.retryAttempt)
        assertFalse(activity.isFinishing)
        if (kind == ContentKind.EPISODE) compose.onNodeWithTag("player-seek-forward").assertIsFocused()
    }

    @Test fun repeatedSkipPressesKeepFocusAndUpCollapsesTheBar() = withPlayer(ContentKind.MOVIE, 1) { activity, controller ->
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("player-seek-back").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("player-seek-forward").assertIsFocused()
        val before = controller.state.value.positionMs
        repeat(3) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            withTimeout(5_000) { controller.state.first { it.phase == Phase.PLAYING } }
            compose.onNodeWithTag("player-seek-forward").assertIsFocused()
        }
        assertTrue(controller.state.value.positionMs >= before + 29_000)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(2) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            withTimeout(5_000) { controller.state.first { it.phase == Phase.PLAYING } }
            compose.onNodeWithTag("player-seek-back").assertIsFocused()
        }
        assertTrue(controller.state.value.positionMs < before + 20_000)
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("10s") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("player-controls").assertDoesNotExist()
        assertEquals(Phase.PLAYING, controller.state.value.phase)
        assertFalse(activity.isFinishing)

        // Opening the bar consumes the whole OK press; it must not immediately pause.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Pause") and hasClickAction()).assertIsFocused()
        assertEquals(Phase.PLAYING, controller.state.value.phase)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(2_000) { controller.state.value.phase == Phase.PAUSED }
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("10s") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        assertEquals(Phase.PAUSED, controller.state.value.phase)
        assertFalse(activity.isFinishing)
    }

    @Test fun backLeavesWhileAnOnDemandSeekIsBuffering() = withPlayer(ContentKind.EPISODE, 9005) { activity, controller ->
        repeat(9) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(3_000) { controller.state.value.phase == Phase.BUFFERING }
        val started = SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(3_000) { activity.isFinishing }
        withTimeout(3_000) { controller.state.first { it.phase == Phase.SUSPENDED } }
        withTimeout(3_000) { controller.openConnections.first { it == 0 } }
        assertTrue(SystemClock.elapsedRealtime() - started < 3_000)
    }

    private fun key(code: Int) {
        inst.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun withPlayer(kind: ContentKind, id: Long, block: suspend (PlayerActivity, PlaybackController) -> Unit) = runBlocking {
        inst.setInTouchMode(false)
        val application = context.applicationContext as HostAppTVApplication
        val original = application.container
        original.sync.cancel(); original.discovery.cancel()
        val app = AppContainer(context)
        application.container = app
        app.activate(ProviderConfig("http://10.0.2.2:18099", "demo", "demo", true))
        var activity: PlayerActivity? = null
        try {
            activity = inst.startActivitySync(PlaybackRequest(kind, id, "mp4", "Synthetic seek test")
                .toIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            lateinit var controller: PlaybackController
            val field = PlayerActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            inst.runOnMainSync { controller = field.get(activity) as PlaybackController }
            withTimeout(20_000) { controller.state.first { it.phase == Phase.PLAYING && it.seekable } }
            compose.waitForIdle()
            block(activity, controller)
        } finally {
            activity?.let { a -> inst.runOnMainSync { a.finish() } }
            app.sync.cancel(); app.discovery.cancel(); app.appScope.cancel()
            application.container = original
        }
    }
}
