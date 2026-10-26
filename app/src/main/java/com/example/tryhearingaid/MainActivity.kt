package com.example.tryhearingaid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.tryhearingaid.service.AudioProcessingService
import com.example.tryhearingaid.ui.AmplifierScreen
import com.example.tryhearingaid.service.ServiceLocator

class MainActivity : ComponentActivity() {

    // ── Service binding ───────────────────────────────────────────────────────

    /**
     * Whether the service is currently bound. Used to guard [unbindService] calls
     * and prevent IllegalArgumentException on rapid lifecycle transitions.
     */
    private var isBound = false

    /**
     * [ServiceConnection] callbacks wired to [ServiceLocator].
     * [onServiceConnected] publishes the service to [ServiceLocator] so ViewModels
     * and other components can resolve it without an Activity reference.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as AudioProcessingService.LocalBinder
            ServiceLocator.onServiceConnected(binder.getService())
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Called when the service crashes or is killed by the OS — not on a
            // normal unbind from our side.
            ServiceLocator.onServiceDisconnected()
            isBound = false
        }
    }
    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the foreground service so it keeps running even when unbound.
        // The service will create its notification and begin audio capture.
        val serviceIntent = Intent(this, AudioProcessingService::class.java)
        startForegroundService(serviceIntent)

        setContent {
            MaterialTheme {
                MainAppScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to the (already-started) service so we can interact with it while
        // the Activity is in the foreground.
        val serviceIntent = Intent(this, AudioProcessingService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Unbind when the Activity is no longer visible, but do NOT stop the service
        // — it continues running as a foreground service in the background.
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

// ── Screen definitions ────────────────────────────────────────────────────────

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Amplifier : Screen("amplifier", "Amplifier", Icons.Default.Hearing)
    object Captions  : Screen("captions",  "Live Text",  Icons.Default.Message)
    object Alerts    : Screen("alerts",    "Alerts",     Icons.Default.NotificationsActive)
    object Analytics : Screen("analytics", "Analytics",  Icons.Default.Timeline)
}

// ── Top-level composable ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Amplifier) }
    val items = listOf(Screen.Amplifier, Screen.Captions, Screen.Alerts, Screen.Analytics)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    NavigationBarItem(
                        icon    = { Icon(screen.icon, contentDescription = screen.title) },
                        label   = { Text(screen.title) },
                        selected = currentScreen == screen,
                        onClick  = { currentScreen = screen }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                is Screen.Amplifier -> AmplifierScreen()
                is Screen.Captions  -> PlaceholderScreen("Live Captions & AI Assistant (Feature 2, 4, 7)")
                is Screen.Alerts    -> PlaceholderScreen("Sound Recognition & Emergency (Feature 3, 6, 9)")
                is Screen.Analytics -> PlaceholderScreen("Hearing Health Dashboard (Feature 5)")
            }
        }
    }
}

@Composable
fun PlaceholderScreen(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
