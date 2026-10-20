package org.hostapptv.player.ui.components

import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Detaches the TV keyboard when focus leaves a text field. Hiding alone is not enough on Android TV:
 * the keyboard can stay bound while invisible and turn D-pad presses into typed characters, so the
 * input connection is restarted after focus has moved.
 */
@Composable
fun rememberImeDetacher(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            view.post { imm?.restartInput(view) }
        }
    }
}
