package org.hostapptv.player

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.hostapptv.player.data.db.SyncStateEntity
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.ui.shell.MainViewModel
import org.hostapptv.player.ui.shell.Route
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Real remote events and the system TV keyboard, using only synthetic local data. */
@RunWith(AndroidJUnit4::class)
class RemoteTextInputTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val editable = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private lateinit var mainActivity: MainActivity

    @Test fun setupFieldsRequireOkAndRemainNavigableAfterBack() = withApp(signedIn = false) {
        field("Server address").assertIsFocused()
        checkEditing("Server address", "https://provider.example")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkEditing("Username", "demo")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        checkEditing("Password", "synthetic-password")
        key(KeyEvent.KEYCODE_DPAD_UP)
        field("Username").assertIsFocused()
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        keyboard(true)
        key(KeyEvent.KEYCODE_BACK)
        keyboard(false)
    }

    @Test fun searchAndTmdbTokenRequireOkAndRemainNavigableAfterBack() = withApp(signedIn = true) {
        navigate("Search")
        field("Search").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        checkEditing("Search", "synthetic search")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_UP)
        field("Search").assertIsFocused()
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        keyboard(true)
        key(KeyEvent.KEYCODE_BACK)
        keyboard(false)

        navigate("Settings")
        val token = "TMDB API Read Access Token"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(token))
        field(token).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        checkEditing(token, "synthetic-token-never-saved")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_UP)
        field(token).assertIsFocused()
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        keyboard(true)
        key(KeyEvent.KEYCODE_BACK)
        keyboard(false)
    }

    private fun checkEditing(label: String, text: String) {
        field(label).assertIsFocused()
        keyboard(false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        keyboard(true)
        field(label).performTextInput(text)
        settle()
        key(KeyEvent.KEYCODE_BACK)
        keyboard(false)
        field(label).assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString(text)))
    }

    private fun field(label: String) = compose.onNode(hasContentDescription(label) and editable, useUnmergedTree = true)

    private fun navigate(label: String) {
        instrumentation.runOnMainSync {
            ViewModelProvider(mainActivity)[MainViewModel::class.java]
                .navigateTop(if (label == "Search") Route.Search else Route.Settings)
        }
        settle()
    }

    private fun key(code: Int) {
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(code)
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        SystemClock.sleep(250)
        compose.waitForIdle()
    }

    private fun keyboard(shown: Boolean) {
        var matches = false
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!matches && SystemClock.uptimeMillis() < deadline) {
            compose.waitForIdle()
            val state = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method").use {
                FileInputStream(it.fileDescriptor).bufferedReader().readText()
            }
            matches = state.contains("mInputShown=$shown")
            if (!matches) SystemClock.sleep(150)
        }
        assertTrue("System keyboard shown=$shown", matches)
    }

    private fun withApp(signedIn: Boolean, test: () -> Unit) = runBlocking {
        val application = context.applicationContext as HostAppTVApplication
        val original = application.container
        original.sync.cancel(); original.discovery.cancel()
        val prefs = context.getSharedPreferences("account", 0)
        val previous = prefs.all.filterKeys { it == "blob" || it == "discovery_blob" }
        prefs.edit().remove("blob").remove("discovery_blob").commit()
        val app = AppContainer(context)
        application.container = app
        var activity: Activity? = null
        try {
            if (signedIn) {
                val session = app.activate(ProviderConfig("http://10.0.2.2:18099", "demo", "demo", true))
                val now = System.currentTimeMillis()
                listOf("live", "movie", "series").forEach {
                    app.library.catalog.upsertSyncState(SyncStateEntity(session.accountId, it, now, now, 0, "complete", null))
                }
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            mainActivity = activity as MainActivity
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(if (signedIn) "Settings" else "Server address"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            settle()
            test()
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            app.sync.cancel(); app.discovery.cancel(); app.appScope.cancel(); app.database.close()
            prefs.edit().remove("blob").remove("discovery_blob").also { editor ->
                previous.forEach { (key, value) -> editor.putString(key, value as String) }
            }.commit()
            application.container = original
        }
    }
}
