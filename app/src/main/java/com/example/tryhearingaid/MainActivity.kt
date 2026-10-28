package com.example.tryhearingaid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.example.tryhearingaid.ui.AlertsScreen
import com.example.tryhearingaid.ui.AnalyticsScreen
import com.example.tryhearingaid.ui.CaptionsScreen
import com.example.tryhearingaid.ui.ProfilesScreen
import com.example.tryhearingaid.ui.SettingsScreen
import com.example.tryhearingaid.ui.permissions.PermissionRationaleScreen
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
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

@AndroidEntryPoint
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

        setContent {
            MaterialTheme {
                var micGranted by remember { mutableStateOf(hasMicPermission()) }
                if (micGranted) {
                    // The microphone foreground service can only start once the permission is granted.
                    LaunchedEffect(Unit) { startAndBindService() }
                    MainAppScreen()
                } else {
                    PermissionRationaleScreen(onAllRequiredGranted = {
                        micGranted = hasMicPermission()
                    })
                }
            }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts the foreground service (so it keeps running in the background) and binds to it. */
    private fun startAndBindService() {
        if (!hasMicPermission()) return
        val serviceIntent = Intent(this, AudioProcessingService::class.java)
        startForegroundService(serviceIntent)
        if (!isBound) {
            isBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-bind when coming back to the foreground (the first launch is handled in setContent,
        // after the microphone permission has been granted).
        if (hasMicPermission()) startAndBindService()
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
    object Profiles  : Screen("profiles",  "Profiles",   Icons.Default.Person)
    object Analytics : Screen("analytics", "Analytics",  Icons.Default.Timeline)
    object Settings  : Screen("settings",  "Settings",   Icons.Default.Settings)
}

// ── Top-level composable ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val items = listOf(
        Screen.Amplifier, Screen.Captions, Screen.Alerts,
        Screen.Profiles, Screen.Analytics, Screen.Settings
    )
    var currentRoute by rememberSaveable { mutableStateOf(Screen.Amplifier.route) }
    val currentScreen = items.firstOrNull { it.route == currentRoute } ?: Screen.Amplifier

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
                        onClick  = { currentRoute = screen.route }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                is Screen.Amplifier -> AmplifierScreen()
                is Screen.Captions  -> CaptionsScreen()
                is Screen.Alerts    -> AlertsScreen()
                is Screen.Profiles  -> ProfilesScreen()
                is Screen.Analytics -> AnalyticsScreen()
                is Screen.Settings  -> SettingsScreen()
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
