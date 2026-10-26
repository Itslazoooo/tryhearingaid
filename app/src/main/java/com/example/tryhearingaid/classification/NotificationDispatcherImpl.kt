package com.example.tryhearingaid.classification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.AlertPriority
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundAlert
import com.example.tryhearingaid.data.SoundDetection
import com.example.tryhearingaid.wearable.WearableBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [NotificationDispatcher].
 *
 * ## Priority mapping (Requirement 9.6, Property 12)
 * - confidence ≥ 0.80 → [AlertPriority.HIGH]
 * - 0.50 ≤ confidence < 0.80 → [AlertPriority.LOW]
 * - confidence < 0.50 → silently ignored
 *
 * ## Channels (Requirement 9.3, Property 13)
 * - [AlertChannel.SCREEN_BANNER] — heads-up notification via [NotificationManager]
 * - [AlertChannel.VIBRATION] — haptic feedback via [Vibrator] / [VibratorManager]
 * - [AlertChannel.FLASHLIGHT] — torch pattern via [CameraManager]
 * - [AlertChannel.SMARTWATCH] — forwarded to [WearableBridge.sendAlert]
 *
 * @param context Application context used for system-service lookups and notifications.
 * @param wearableBridge Optional bridge to connected smartwatches. If null the SMARTWATCH
 *        channel is silently skipped (Task 15 will supply the real implementation).
 */
@Singleton
class NotificationDispatcherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wearableBridge: WearableBridge? = null
) : NotificationDispatcher {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NotificationDispatcher"

        /** Notification channel ID used for all sound-alert heads-up banners. */
        const val SOUND_ALERT_CHANNEL_ID = "sound_alerts"

        /** Base notification ID; incremented per alert to avoid collapsing them. */
        private const val NOTIFICATION_ID_BASE = 9000

        // ── Priority thresholds (Requirement 9.6) ────────────────────────────
        const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
        const val LOW_CONFIDENCE_MIN = 0.50f

        // ── Vibration patterns ────────────────────────────────────────────────
        /** HIGH: long burst — on 500 ms, off 200 ms, on 500 ms. */
        private val VIBRATION_PATTERN_HIGH = longArrayOf(0L, 500L, 200L, 500L)

        /** LOW: single short pulse — 200 ms. */
        private const val VIBRATION_DURATION_LOW = 200L

        // ── Flashlight timing (ms) ────────────────────────────────────────────
        private const val FLASH_SHORT_ON = 100L
        private const val FLASH_SHORT_OFF = 100L
        private const val FLASH_LONG_ON = 500L
        private const val FLASH_LONG_OFF = 300L
        private const val FLASH_INTER_GROUP = 700L // gap between S/L/S groups in SOS
        private const val FLASH_REPEAT = 3          // number of flashes per pattern
    }

    // ── System services ───────────────────────────────────────────────────────

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile
    private var enabledChannels: Set<AlertChannel> = AlertChannel.entries.toSet()

    @Volatile
    private var currentPattern: FlashlightPattern = FlashlightPattern.SHORT

    private val notificationIdCounter = AtomicInteger(NOTIFICATION_ID_BASE)

    // ── Coroutine scope for async flashlight operations ───────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        createNotificationChannel()
    }

    /**
     * Create the [NotificationChannel] for sound alerts.
     * Safe to call multiple times — the system ignores duplicate channel creation.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SOUND_ALERT_CHANNEL_ID,
            "Sound Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for detected environmental sounds"
            enableVibration(false) // vibration handled separately
        }
        notificationManager.createNotificationChannel(channel)
    }

    // ── NotificationDispatcher API ────────────────────────────────────────────

    /**
     * Map [detection.confidence] to [AlertPriority] and dispatch to all enabled channels.
     *
     * The [priority] parameter supplied by the caller is **ignored** — priority is always
     * derived from confidence to ensure correctness (Property 12, Requirement 9.6).
     */
    override fun dispatch(detection: SoundDetection, priority: AlertPriority) {
        val resolvedPriority = mapToPriority(detection.confidence) ?: return // < 0.50: ignore

        Log.d(TAG, "Dispatching ${detection.category} confidence=${detection.confidence} " +
                "priority=$resolvedPriority channels=$enabledChannels")

        for (channel in enabledChannels) {
            when (channel) {
                AlertChannel.SCREEN_BANNER -> dispatchScreenBanner(detection, resolvedPriority)
                AlertChannel.VIBRATION     -> dispatchVibration(resolvedPriority)
                AlertChannel.FLASHLIGHT    -> dispatchFlashlight()
                AlertChannel.SMARTWATCH    -> dispatchSmartwatch(detection, resolvedPriority)
            }
        }
    }

    override fun setChannels(channels: Set<AlertChannel>) {
        enabledChannels = channels.toSet()
        Log.d(TAG, "Alert channels updated: $enabledChannels")
    }

    override fun setFlashlightPattern(pattern: FlashlightPattern) {
        currentPattern = pattern
        Log.d(TAG, "Flashlight pattern set to: $pattern")
    }

    override fun release() {
        Log.d(TAG, "Releasing NotificationDispatcherImpl resources.")
        scope.cancel()
    }

    // ── Priority mapping ──────────────────────────────────────────────────────

    /**
     * Map a raw confidence score to [AlertPriority], or null if below the minimum threshold.
     *
     * Property 12 / Requirement 9.6:
     * - ≥ 0.80 → HIGH
     * - 0.50 ≤ x < 0.80 → LOW
     * - < 0.50 → null (no dispatch)
     */
    fun mapToPriority(confidence: Float): AlertPriority? = when {
        confidence >= HIGH_CONFIDENCE_THRESHOLD -> AlertPriority.HIGH
        confidence >= LOW_CONFIDENCE_MIN        -> AlertPriority.LOW
        else                                    -> null
    }

    // ── Channel dispatchers ───────────────────────────────────────────────────

    /**
     * Post a heads-up notification banner for the detected sound.
     *
     * HIGH priority → [NotificationCompat.PRIORITY_HIGH] with full-screen intent eligibility.
     * LOW priority  → [NotificationCompat.PRIORITY_DEFAULT].
     *
     * Requirement 9.3
     */
    private fun dispatchScreenBanner(detection: SoundDetection, priority: AlertPriority) {
        val notifPriority = if (priority == AlertPriority.HIGH) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val title = formatCategoryName(detection.category.name)
        val text  = "Detected with ${(detection.confidence * 100).toInt()}% confidence"

        val notification = NotificationCompat.Builder(context, SOUND_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(notifPriority)
            .setAutoCancel(true)
            .build()

        val id = notificationIdCounter.getAndIncrement()
        try {
            notificationManager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }

    /**
     * Trigger a vibration alert appropriate to [priority].
     *
     * HIGH → wave pattern (long-short-long).
     * LOW  → single short pulse.
     *
     * Requirement 9.3
     */
    private fun dispatchVibration(priority: AlertPriority) {
        try {
            if (priority == AlertPriority.HIGH) {
                val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN_HIGH, -1)
                vibrator.vibrate(effect)
            } else {
                val effect = VibrationEffect.createOneShot(
                    VIBRATION_DURATION_LOW,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    /**
     * Run the configured [FlashlightPattern] asynchronously on the IO dispatcher.
     *
     * Requirement 9.3, 9.4, Property 13
     */
    private fun dispatchFlashlight() {
        val pattern = currentPattern
        scope.launch {
            val cameraId = backFacingCameraId() ?: run {
                Log.w(TAG, "No back-facing camera found for flashlight alert.")
                return@launch
            }
            try {
                when (pattern) {
                    FlashlightPattern.SHORT -> flashShort(cameraId)
                    FlashlightPattern.LONG  -> flashLong(cameraId)
                    FlashlightPattern.SOS   -> flashSos(cameraId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Flashlight dispatch failed: ${e.message}")
                // Ensure torch is always left off
                safeSetTorch(cameraId, false)
            }
        }
    }

    /**
     * Forward the alert to the connected smartwatch via [WearableBridge].
     *
     * Null-safe: if [wearableBridge] is not injected (Task 15 not yet implemented),
     * this channel silently does nothing.
     *
     * Requirement 9.3
     */
    private fun dispatchSmartwatch(detection: SoundDetection, priority: AlertPriority) {
        val bridge = wearableBridge ?: return
        val alert = SoundAlert(
            category    = detection.category,
            confidence  = detection.confidence,
            priority    = priority,
            description = null, // AI description added in Task 16
            detectedAtMs = detection.timestampMs
        )
        try {
            bridge.sendAlert(alert)
        } catch (e: Exception) {
            Log.w(TAG, "WearableBridge.sendAlert failed: ${e.message}")
        }
    }

    // ── Flashlight helpers ────────────────────────────────────────────────────

    /**
     * 3 short flashes: on [FLASH_SHORT_ON] ms, off [FLASH_SHORT_OFF] ms, × [FLASH_REPEAT].
     */
    private suspend fun flashShort(cameraId: String) {
        repeat(FLASH_REPEAT) {
            safeSetTorch(cameraId, true)
            delay(FLASH_SHORT_ON)
            safeSetTorch(cameraId, false)
            delay(FLASH_SHORT_OFF)
        }
    }

    /**
     * 3 long flashes: on [FLASH_LONG_ON] ms, off [FLASH_LONG_OFF] ms, × [FLASH_REPEAT].
     */
    private suspend fun flashLong(cameraId: String) {
        repeat(FLASH_REPEAT) {
            safeSetTorch(cameraId, true)
            delay(FLASH_LONG_ON)
            safeSetTorch(cameraId, false)
            delay(FLASH_LONG_OFF)
        }
    }

    /**
     * SOS pattern: 3 short, 3 long, 3 short with [FLASH_INTER_GROUP] gap between groups.
     * Timing follows the international distress signal convention.
     */
    private suspend fun flashSos(cameraId: String) {
        // S — 3 short flashes
        repeat(3) {
            safeSetTorch(cameraId, true)
            delay(FLASH_SHORT_ON)
            safeSetTorch(cameraId, false)
            delay(FLASH_SHORT_OFF)
        }
        delay(FLASH_INTER_GROUP)

        // O — 3 long flashes
        repeat(3) {
            safeSetTorch(cameraId, true)
            delay(FLASH_LONG_ON)
            safeSetTorch(cameraId, false)
            delay(FLASH_LONG_OFF)
        }
        delay(FLASH_INTER_GROUP)

        // S — 3 short flashes
        repeat(3) {
            safeSetTorch(cameraId, true)
            delay(FLASH_SHORT_ON)
            safeSetTorch(cameraId, false)
            delay(FLASH_SHORT_OFF)
        }
    }

    /** Toggle the camera torch; swallows [SecurityException] when CAMERA is not granted. */
    private fun safeSetTorch(cameraId: String, enabled: Boolean) {
        try {
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode($enabled) failed: ${e.message}")
        }
    }

    /**
     * Return the first back-facing camera ID, or null if none is available.
     */
    private fun backFacingCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query camera list: ${e.message}")
            null
        }
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    /**
     * Convert a SCREAMING_SNAKE_CASE enum name to Title Case.
     * e.g. "SMOKE_ALARM" → "Smoke Alarm"
     */
    internal fun formatCategoryName(name: String): String =
        name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
}
