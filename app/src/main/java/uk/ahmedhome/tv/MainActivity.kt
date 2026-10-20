package uk.ahmedhome.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import uk.ahmedhome.tv.ui.shell.AppRoot
import uk.ahmedhome.tv.ui.shell.MainViewModel
import uk.ahmedhome.tv.ui.theme.AhmedHomeTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AhmedHomeTheme { AppRoot(vm) } }
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
