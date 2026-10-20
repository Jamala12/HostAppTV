package org.hostapptv.player.ui.components

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.MaterialTheme
import org.hostapptv.player.ui.theme.Ink

/** Every TV input separates remote focus from keyboard editing. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onFocusChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit,
    decorationBox: @Composable (inner: @Composable () -> Unit) -> Unit = { it() },
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val editFocus = remember { FocusRequester() }
    val detachIme = rememberImeDetacher()
    LaunchedEffect(editing) {
        if (editing) keyboard?.show() else detachIme()
    }
    fun submit() { onSubmit(); editing = false; detachIme() }
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true, readOnly = !editing,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text),
        cursorBrush = SolidColor(Ink.sage), visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onNext = { submit() }, onDone = { submit() }, onSearch = { submit() }),
        modifier = modifier.focusRequester(editFocus).onFocusChanged {
            if (focused && !it.isFocused) { editing = false; detachIme() }
            focused = it.isFocused
            onFocusChange(it.isFocused)
        }.onPreInterceptKeyBeforeSoftKeyboard { event ->
            // An invisible TV keyboard can still swallow arrows, so navigation runs before it.
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            if (editing && event.key == Key.Back) {
                if (event.type == KeyEventType.KeyUp) editing = false
                true
            } else if (!editing && direction != null) {
                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                true
            } else if (!editing && (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                if (event.type == KeyEventType.KeyUp) editing = true
                true
            } else false
        }.semantics { onClick(label = "Edit") { editFocus.requestFocus(); editing = true; true } },
        decorationBox = decorationBox,
    )
}
