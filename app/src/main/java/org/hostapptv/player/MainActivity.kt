package org.hostapptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.hostapptv.player.ui.shell.AppRoot
import org.hostapptv.player.ui.shell.MainViewModel
import org.hostapptv.player.ui.theme.HostAppTVTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HostAppTVTheme { AppRoot(vm) } }
    }

    override fun onStart() {
        super.onStart()
        container.sync.setForeground(true)
        container.discovery.refresh()
    }

    override fun onStop() {
        container.sync.setForeground(false)
        container.discovery.cancel()
        super.onStop()
    }
}
