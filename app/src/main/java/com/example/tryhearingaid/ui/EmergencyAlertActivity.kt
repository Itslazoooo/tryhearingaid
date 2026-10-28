package com.example.tryhearingaid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tryhearingaid.MainActivity
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.emergency.EmergencyAlertText

/**
 * Full-screen emergency alert. It turns the screen on and shows over the lock screen, so an
 * emergency sound is noticed even when the phone is in a pocket or asleep (Requirement 10.2).
 */
class EmergencyAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent { AlertContent(intent) }
    }

    // A second alert while this one is open updates it instead of stacking.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { AlertContent(intent) }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @androidx.compose.runtime.Composable
    private fun AlertContent(intent: Intent) {
        val category = runCatching {
            SoundCategory.valueOf(intent.getStringExtra(EXTRA_CATEGORY) ?: "")
        }.getOrNull()
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB71C1C))
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("EMERGENCY", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = category?.let { EmergencyAlertText.title(it) } ?: "Emergency sound",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Text(
                    EmergencyAlertText.detail(confidence),
                    color = Color.White,
                    fontSize = 16.sp
                )
                Button(
                    onClick = { finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp).height(64.dp)
                ) { Text("Dismiss", fontSize = 20.sp) }
                OutlinedButton(
                    onClick = {
                        startActivity(Intent(this@EmergencyAlertActivity, MainActivity::class.java))
                        finish()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("Open app", color = Color.White) }
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_CONFIDENCE = "confidence"
    }
}
