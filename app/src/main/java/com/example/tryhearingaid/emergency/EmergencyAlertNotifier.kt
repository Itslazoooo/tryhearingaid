package com.example.tryhearingaid.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.ui.EmergencyAlertActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The words shown for an emergency sound. Separate from Android so it can be tested. */
object EmergencyAlertText {
    fun title(category: SoundCategory): String = when (category) {
        SoundCategory.FIRE_ALARM, SoundCategory.SMOKE_ALARM -> "Fire alarm"
        SoundCategory.CO_ALARM -> "Carbon monoxide alarm"
        SoundCategory.GLASS_BREAKING -> "Glass breaking"
        SoundCategory.VERBAL_HELP -> "Someone is calling for help"
        SoundCategory.SIREN, SoundCategory.FIRE_TRUCK_SIREN, SoundCategory.AMBULANCE_SIREN,
        SoundCategory.POLICE_SIREN -> "Emergency siren"
        else -> category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun detail(confidence: Float): String = "Detected with ${(confidence * 100).toInt()}% confidence"
}

/**
 * Shows an emergency sound as a full-screen alert that wakes the screen, also over the lock
 * screen (Requirement 10.2). It is a notification with a full-screen intent, which is the only
 * way Android lets a background app open a screen. Where the phone does not allow full-screen
 * alerts (Settings shows how to allow them), it appears as a loud pop-up notification instead.
 */
@Singleton
class EmergencyAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        val channel = NotificationChannel(CHANNEL_ID, "Emergency sounds", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "Fire alarms, sirens and other emergency sounds"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true) // only takes effect if the user allows it for this app
            }
        manager.createNotificationChannel(channel)
    }

    fun show(event: EmergencyEvent) {
        val intent = Intent(context, EmergencyAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EmergencyAlertActivity.EXTRA_CATEGORY, event.category.name)
            .putExtra(EmergencyAlertActivity.EXTRA_CONFIDENCE, event.confidence)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("EMERGENCY: " + EmergencyAlertText.title(event.category))
            .setContentText(EmergencyAlertText.detail(event.confidence))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "EmergencyAlertNotifier"
        const val CHANNEL_ID = "emergency_sounds"
        const val NOTIFICATION_ID = 4001
    }
}
