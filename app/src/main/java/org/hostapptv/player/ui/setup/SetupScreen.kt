package org.hostapptv.player.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.hostapptv.player.ui.components.BrandLockup
import org.hostapptv.player.ui.components.ButtonTone
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.components.rememberImeDetacher
import org.hostapptv.player.ui.shell.ConnectState
import org.hostapptv.player.ui.shell.MainViewModel
import org.hostapptv.player.ui.shell.SetupForm
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink

@Composable
fun SetupScreen(vm: MainViewModel) {
    val connect by vm.connect.collectAsState()
    // Password is deliberately not saveable state; it lives only in this composition.
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val serverFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val checking = connect is ConnectState.Checking
    val failed = connect as? ConnectState.Failed
    val isHttp = server.trim().startsWith("http://", ignoreCase = true) || (server.isNotBlank() && !server.contains("://"))

    LaunchedEffect(Unit) { runCatching { serverFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().background(Ink.canvas).padding(horizontal = Dimens.safeH, vertical = Dimens.safeV)) {
        Column(Modifier.width(300.dp).padding(top = 12.dp, end = 32.dp)) {
            BrandLockup()
            Spacer(Modifier.height(40.dp))
            Text("Connect your provider", style = MaterialTheme.typography.displaySmall, color = Ink.heading)
            Spacer(Modifier.height(14.dp))
            Text(
                "Enter the Xtream details from your IPTV provider. This TV connects directly to your provider.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted,
            )
            Spacer(Modifier.height(14.dp))
            Text("Remembered details are encrypted on this device and never backed up.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("Server address", server, { server = it; vm.clearConnectError() }, "http://provider.example:8080",
                Modifier.focusRequester(serverFocus), keyboardType = KeyboardType.Uri)
            Field("Username", username, { username = it; vm.clearConnectError() }, "", keyboardType = KeyboardType.Ascii)
            Row(verticalAlignment = Alignment.Bottom) {
                Field("Password", password, { password = it; vm.clearConnectError() }, "", Modifier.weight(1f),
                    keyboardType = KeyboardType.Password, hidden = !showPassword, imeAction = ImeAction.Done)
                Spacer(Modifier.width(12.dp))
                TvButton(if (showPassword) "Hide" else "Show", { showPassword = !showPassword })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val toConnect = Modifier.focusProperties { down = connectFocus }
                TvButton("Remember on this TV", { remember = !remember }, toConnect, icon = if (remember) AppIcons.Check else null, selected = remember)
                if (isHttp) TvButton("Allow unencrypted HTTP", { allowHttp = !allowHttp; vm.clearConnectError() }, toConnect, icon = if (allowHttp) AppIcons.Check else null, selected = allowHttp)
            }
            if (isHttp) Text(
                "HTTP sends your password and video without encryption. Only allow it if your provider has no https:// address.",
                style = MaterialTheme.typography.bodySmall, color = if (failed?.needsHttpConsent == true) Ink.danger else Ink.muted,
            )
            if (failed != null) Text(failed.message, style = MaterialTheme.typography.bodyMedium, color = Ink.danger,
                modifier = Modifier.semantics { contentDescription = "Error: ${failed.message}" })
            TvButton(
                if (checking) "Checking…" else "Connect",
                { if (!checking) vm.submit(SetupForm(server, username, password, remember, allowHttp)) },
                Modifier.focusRequester(connectFocus), icon = AppIcons.Play, tone = ButtonTone.Primary, enabled = !checking,
            )
        }
    }
}

@Composable
internal fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text, hidden: Boolean = false, imeAction: ImeAction = ImeAction.Next,
) {
    var focused by remember { mutableStateOf(false) }
    val fm = LocalFocusManager.current
    val detachIme = rememberImeDetacher()
    // Leaving a field must also detach the TV keyboard, or it keeps swallowing D-pad keys after it hides.
    fun advance() { fm.moveFocus(FocusDirection.Down); detachIme() }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) Ink.text else Ink.muted)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text),
            cursorBrush = SolidColor(Ink.sage),
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { advance() }, onDone = { advance() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (focused && !it.isFocused) detachIme(); focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.key == Key.Enter || ev.key == Key.NumPadEnter) {
                        if (ev.type == KeyEventType.KeyUp) advance()
                        true
                    } else false
                }
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(Dimens.controlHeight)
                        .background(Ink.control, RoundedCornerShape(Dimens.radiusSmall + 2.dp))
                        .border(BorderStroke(if (focused) Dimens.focusBorder else 1.dp, if (focused) Ink.focus else Ink.controlLine), RoundedCornerShape(Dimens.radiusSmall + 2.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ink.muted.copy(alpha = 0.7f))
                    inner()
                }
            },
        )
    }
}
