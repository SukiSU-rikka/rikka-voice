package com.rikka.voice.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rikka.voice.core.Engines
import com.rikka.voice.service.HostService

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engines.init(this)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        setContent { RikkaTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** rikkavoice://start（六花日语唤起）或用户之前开着接口 → 启动接口。 */
    private fun handleIntent(i: Intent?) {
        val fromRikka = i?.data?.scheme == "rikkavoice"
        if (fromRikka || Engines.prefs.serverEnabled) runCatching { HostService.start(this) }
        if (fromRikka && i?.data?.getQueryParameter("back") == "1") moveTaskToBack(true)
    }
}

@androidx.compose.runtime.Composable
private fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Triple("首页", Icons.Rounded.Home, 0),
        Triple("声音", Icons.Rounded.RecordVoiceOver, 1),
        Triple("电脑", Icons.Rounded.Computer, 2),
        Triple("关于", Icons.Rounded.Info, 3),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> HomeScreen(m) { tab = 1 }
            1 -> VoicesScreen(m)
            2 -> RemoteScreen(m)
            else -> AboutScreen(m)
        }
    }
}
