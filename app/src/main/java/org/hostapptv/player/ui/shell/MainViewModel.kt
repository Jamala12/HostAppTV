package org.hostapptv.player.ui.shell

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.Session
import org.hostapptv.player.container
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.data.xtream.XtreamClient
import org.hostapptv.player.data.xtream.XtreamException

sealed interface Route {
    data object Search : Route
    data object Home : Route
    data object Live : Route
    data object Movies : Route
    data object Series : Route
    data object MyList : Route
    data object Settings : Route
    data class MovieDetail(val groupKey: String, val streamId: Long) : Route
    data class SeriesDetail(val seriesId: Long) : Route

    val isTopLevel: Boolean get() = this !is MovieDetail && this !is SeriesDetail
    val key: String get() = toString()
}

sealed interface AppState {
    data object Starting : AppState
    data class NeedsSetup(val reason: String? = null) : AppState
    data class Ready(val session: Session) : AppState
}

data class SetupForm(val server: String = "", val username: String = "", val password: String = "", val remember: Boolean = true, val allowHttp: Boolean = false)

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Checking : ConnectState
    data class Failed(val message: String, val needsHttpConsent: Boolean = false) : ConnectState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow<AppState>(AppState.Starting)
    val state: StateFlow<AppState> = _state
    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect

    /** Top-level destination plus detail screens pushed on top. */
    val stack = mutableStateListOf<Route>(Route.Home)

    init {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { c.session.value ?: c.restoreSession() }
            _state.value = if (s != null) { maybeSync(s); AppState.Ready(s) } else AppState.NeedsSetup()
        }
    }

    private fun maybeSync(s: Session) {
        viewModelScope.launch(Dispatchers.IO) {
            val states = c.library.catalog.syncStatesNow(s.accountId)
            val stale = states.size < 3 || states.any { it.lastCompleteAt == null || System.currentTimeMillis() - it.lastCompleteAt > 12 * 3600_000L }
            if (stale) c.sync.refresh(s.accountId, s.client)
        }
    }

    fun submit(form: SetupForm) {
        val server = form.server.trim()
        val base = XtreamClient.normalizeBaseUrl(server)
        if (base == null || form.username.isBlank() || form.password.isEmpty()) {
            _connect.value = ConnectState.Failed("Enter the server address, username and password.")
            return
        }
        val config = ProviderConfig(base.toString().trimEnd('/'), form.username.trim(), form.password, form.allowHttp)
        if (config.isCleartext && !form.allowHttp) {
            _connect.value = ConnectState.Failed(
                "This server uses HTTP. Your password and video would travel without encryption. Allow HTTP for this provider to continue, or use an https:// address.",
                needsHttpConsent = true,
            )
            return
        }
        _connect.value = ConnectState.Checking
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { c.clientFor(config).authenticate() }
                if (!info.active) throw XtreamException.AccountInactive(info.status)
                c.accountStore.save(config, form.remember)
                val s = c.activate(config)
                _connect.value = ConnectState.Idle
                stack.clear(); stack.add(Route.Home)
                _state.value = AppState.Ready(s)
                c.sync.refresh(s.accountId, s.client)
            } catch (e: XtreamException) {
                _connect.value = ConnectState.Failed(
                    when (e) {
                        is XtreamException.Unauthorized -> "The provider didn't accept that username and password."
                        is XtreamException.AccountInactive -> "This account isn't active${e.status?.let { " (status: $it)" } ?: ""}."
                        is XtreamException.Network -> "Couldn't reach the server. Check the address and the Fire TV's connection."
                        is XtreamException.Http -> "The server answered with an error (HTTP ${e.code}). Check the address."
                        is XtreamException.Malformed -> "That address didn't respond like an Xtream provider. Check the address and port."
                        is XtreamException.CleartextRefused -> "HTTP is blocked for this provider."
                        is XtreamException.SuspiciousEmpty -> e.message ?: "Empty response"
                    },
                )
            }
        }
    }

    fun clearConnectError() { if (_connect.value is ConnectState.Failed) _connect.value = ConnectState.Idle }

    fun navigateTop(route: Route) { stack.clear(); stack.add(route) }
    fun push(route: Route) { stack.add(route) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

    fun signOut() {
        c.signOut()
        stack.clear(); stack.add(Route.Home)
        _state.value = AppState.NeedsSetup()
    }

    fun refreshLibrary() {
        val s = (state.value as? AppState.Ready)?.session ?: return
        c.sync.refresh(s.accountId, s.client)
    }
}
