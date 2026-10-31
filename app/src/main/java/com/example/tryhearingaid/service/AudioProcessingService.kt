package com.example.tryhearingaid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.tryhearingaid.MainActivity
import com.example.tryhearingaid.R
import com.example.tryhearingaid.audio.AudioConfig
import com.example.tryhearingaid.audio.AudioDevice
import com.example.tryhearingaid.audio.AudioEngineImpl
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.audio.AudioRoutePolicy
import com.example.tryhearingaid.audio.DirectionalFilterImpl
import com.example.tryhearingaid.audio.EqualizerImpl
import com.example.tryhearingaid.audio.NoiseReducerImpl
import com.example.tryhearingaid.audio.SoundClassifierTap
import com.example.tryhearingaid.audio.VoiceIsolatorImpl
import com.example.tryhearingaid.captions.CaptionEngine
import com.example.tryhearingaid.classification.AlertSettings
import com.example.tryhearingaid.classification.EmergencyDetectorImpl
import com.example.tryhearingaid.classification.NotificationDispatcher
import com.example.tryhearingaid.data.AlertPriority
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.data.SoundAlert
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.classification.SoundClassifierImpl
import com.example.tryhearingaid.wearable.CaptionForwarder
import com.example.tryhearingaid.wearable.WearableBridgeImpl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns and orchestrates the audio processing pipeline.
 *
 * - Declared with `foregroundServiceType="microphone"` in AndroidManifest.xml
 *   (Requirement 1.3).
 * - Publishes [engineState] as a [StateFlow] so ViewModels can observe engine state
 *   without a direct reference to [AudioEngineImpl].
 * - Exposes a [LocalBinder] so bound clients (Activities/ViewModels) can obtain
 *   a reference to this service instance.
 * - Creates a [NotificationChannel] (API 26+) and starts a foreground notification
 *   immediately in [onStartCommand] to satisfy Android's foreground-service
 *   requirements and prevent OS termination during active audio processing.
 */
@AndroidEntryPoint
class AudioProcessingService : Service() {

    // ── Notification constants ────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioProcessingService"

        /** ID for the foreground-service notification channel. */
        const val NOTIFICATION_CHANNEL_ID = "audio_processing"

        /** Stable notification ID for the foreground notification. */
        private const val NOTIFICATION_ID = 1001
    }

    // ── Audio engine ──────────────────────────────────────────────────────────

    // Everything below is injected by Hilt and shared app-wide. The ViewModels receive these very
    // same objects, so a gain change or a profile switch on screen reaches the audio that is
    // actually playing. They outlive the service, so [onDestroy] must not release them.

    /** The app-wide audio engine. */
    @Inject
    lateinit var audioEngine: AudioEngineImpl

    /**
     * Hands the microphone audio to [soundClassifier], one second at a time. First stage in the
     * pipeline, so the classifier hears the raw sound before any processing.
     */
    @Inject
    lateinit var classifierTap: SoundClassifierTap

    /**
     * [DirectionalFilterImpl], second stage in the pipeline.
     * On single-mic devices it acts as a transparent pass-through.
     */
    @Inject
    lateinit var directionalFilter: DirectionalFilterImpl

    /** [NoiseReducerImpl], applied after the directional filter. */
    @Inject
    lateinit var noiseReducer: NoiseReducerImpl

    /** [VoiceIsolatorImpl], applied after [noiseReducer]. */
    @Inject
    lateinit var voiceIsolator: VoiceIsolatorImpl

    /**
     * [EqualizerImpl], applied after the voice isolator: per-band IIR gain before the adaptive
     * gain and AudioTrack output.
     */
    @Inject
    lateinit var equalizer: EqualizerImpl

    /** [SoundClassifierImpl] that runs YAMNet classification on the windows from [classifierTap]. */
    @Inject
    lateinit var soundClassifier: SoundClassifierImpl

    /**
     * [EmergencyDetectorImpl] subscribed to [soundClassifier]'s detections flow.
     *
     * Started in [onCreate] and stopped in [onDestroy] so that emergency detection stays active
     * even when other features are paused. Requirement 10.5.
     */
    @Inject
    lateinit var emergencyDetector: EmergencyDetectorImpl

    /** Delivers sound alerts as banner, vibration, flashlight and smartwatch alerts. */
    @Inject
    lateinit var notificationDispatcher: NotificationDispatcher

    /** Where the user's alert choices are saved. */
    @Inject
    lateinit var preferences: PreferencesRepository

    /**
     * Sounds the user wants ordinary alerts for. Kept separate from the classifier's own list,
     * which always also contains the life-safety sounds (see [AlertSettings.classifierCategories]).
     */
    @Volatile
    private var alertCategories: Set<SoundCategory> = SoundCategory.entries.toSet()

    /**
     * Partial wake lock held for the whole service lifetime so the CPU keeps running, and the
     * emergency detector keeps listening, while the screen is off (Requirement 10.5).
     * It is the main battery cost of the service; it is released in [onDestroy].
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * [WearableBridgeImpl] that manages Bluetooth (A2DP/HFP) and BLE wearable
     * connections. Lazily initialised in [onCreate] once a Context is available.
     *
     * The service observes [WearableBridgeImpl.connectedDevices] and automatically
     * routes the [AudioEngineImpl] output to a connected A2DP device, satisfying
     * Requirement 14.2.
     *
     * Injected by Hilt so this is the *same* singleton the notification dispatcher
     * uses; a privately constructed bridge would have its own empty device list.
     * Available from [onCreate] after `super.onCreate()`.
     */
    /**
     * Injected only so it is created (and starts measuring exposure) with the service; the
     * Analytics screen reads the same singleton.
     */
    @Inject
    lateinit var analyticsEngine: com.example.tryhearingaid.analytics.AnalyticsEngine

    /** Injected so saved profiles are loaded and the last active one re-applied on start. */
    @Inject
    lateinit var profileManager: com.example.tryhearingaid.profiles.ProfileManager

    /** Decides when the engine runs: only with headphones, at the chosen sample rate. */
    @Inject
    lateinit var amplifierController: com.example.tryhearingaid.audio.AmplifierController

    /** Shows emergency sounds as a full-screen alert that wakes the screen. */
    @Inject
    lateinit var emergencyAlertNotifier: com.example.tryhearingaid.emergency.EmergencyAlertNotifier

    /** Offline captions listen to the same audio as the amplifier, so the two never fight over the microphone. */
    @Inject
    lateinit var offlineCaptions: com.example.tryhearingaid.captions.VoskCaptionEngine

    @Inject
    lateinit var wearableBridge: WearableBridgeImpl

    /**
     * The app-wide [CaptionEngine] singleton, shared with the caption ViewModel.
     * The service observes its captions to forward them to display-capable
     * wearables (Requirement 14.6) even when the UI is in the background.
     */
    @Inject
    lateinit var captionEngine: CaptionEngine

    /**
     * Coroutine scope used to observe wearable connection state and drive
     * automatic audio-output routing. Cancelled in [onDestroy].
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Public [StateFlow] of [AudioEngineState] backed by [audioEngine]'s own flow.
     * ViewModels collect this flow via the bound service reference.
     */
    val engineState: StateFlow<AudioEngineState> get() = audioEngine.state

    /**
     * Convenience accessor so bound clients can interact with the engine directly
     * (e.g., to call [AudioEngineImpl.setGain] or [AudioEngineImpl.addProcessingStage]).
     */
    val engine: AudioEngineImpl get() = audioEngine

    /** Exposes [NoiseReducerImpl] to bound clients (e.g. AudioViewModel). */
    val noiseReducerStage: NoiseReducerImpl get() = noiseReducer

    /** Exposes [VoiceIsolatorImpl] to bound clients (e.g. AudioViewModel). */
    val voiceIsolatorStage: VoiceIsolatorImpl get() = voiceIsolator

    /** Exposes [DirectionalFilterImpl] to bound clients (e.g. AudioViewModel). */
    val directionalFilterStage: DirectionalFilterImpl get() = directionalFilter

    /** Exposes [EqualizerImpl] to bound clients (e.g. AudioViewModel, ProfileViewModel). */
    val equalizerStage: EqualizerImpl get() = equalizer

    /**
     * Exposes [SoundClassifierImpl] to bound clients (e.g. AlertViewModel).
     * Bound clients can submit audio frames or observe [SoundClassifier.detections].
     */
    val soundClassifierStage: SoundClassifierImpl get() = soundClassifier

    /**
     * Exposes [EmergencyDetectorImpl] to bound clients (e.g. AlertViewModel).
     * Provides access to [EmergencyDetector.emergencyEvents] flow.
     */
    val emergencyDetectorStage: EmergencyDetectorImpl get() = emergencyDetector

    /**
     * Exposes [WearableBridgeImpl] to bound clients (e.g. SettingsScreen device
     * list, future caption-routing wiring). Provides access to
     * [WearableBridgeImpl.connectedDevices] for UI display.
     */
    val wearableBridgeStage: WearableBridgeImpl get() = wearableBridge

    // ── Binder ────────────────────────────────────────────────────────────────

    /**
     * Binder returned to clients that bind to this service via
     * [android.content.Context.bindService]. Provides direct access to the
     * [AudioProcessingService] instance.
     */
    inner class LocalBinder : Binder() {
        /** Return the enclosing [AudioProcessingService] instance. */
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }

    private val binder = LocalBinder()

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        // Wire the (injected, shared) stages into the pipeline in order:
        // SoundClassifierTap → DirectionalFilter → NoiseReducer → VoiceIsolator → Equalizer
        // (Adaptive gain and AudioTrack output are applied after all stages.)
        // The engine ignores a stage it already has, so a restarted service adds nothing twice.
        audioEngine.addProcessingStage(classifierTap)
        audioEngine.addProcessingStage(offlineCaptions)
        audioEngine.addProcessingStage(directionalFilter)
        audioEngine.addProcessingStage(noiseReducer)
        audioEngine.addProcessingStage(voiceIsolator)
        audioEngine.addProcessingStage(equalizer)

        // The classifier starts switched off and ignores audio until it is enabled.
        soundClassifier.setEnabled(true)

        // EmergencyDetector subscribes to SoundClassifier.detections and must
        // remain active for the entire service lifetime (Requirement 10.5).
        emergencyDetector.start()
        Log.d(TAG, "EmergencyDetector started — always-on subscription active.")
        acquireWakeLock()

        startWearableIntegration()
        startAlertIntegration()
    }

    /**
     * Connects sound detection to the user's alerts (Requirements 9.2 to 9.6).
     *
     * 1. Applies the saved alert settings to the classifier and dispatcher, and again whenever
     *    the user changes them.
     * 2. Sends each detected sound the user has enabled to the dispatcher, which decides the
     *    priority from the confidence and uses the enabled channels. This runs here, in the
     *    service, so alerts keep arriving with the screen off or the app in the background.
     */
    private fun startAlertIntegration() {
        serviceScope.launch {
            preferences.userPreferences
                .map { AlertSettings.from(it) }
                .distinctUntilChanged()
                .collect { settings ->
                    alertCategories = settings.categories
                    soundClassifier.setEnabledCategories(settings.classifierCategories)
                    notificationDispatcher.setChannels(settings.channels)
                    notificationDispatcher.setFlashlightPattern(settings.flashlightPattern)
                }
        }

        serviceScope.launch {
            soundClassifier.detections.collect { detection ->
                if (detection.category !in alertCategories) return@collect
                try {
                    // The priority argument is ignored; the dispatcher derives it from confidence.
                    notificationDispatcher.dispatch(detection, AlertPriority.LOW)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not dispatch alert for ${detection.category}: ${e.message}")
                }
            }
        }
    }

    /** Take the partial wake lock. Does nothing if the device has no [PowerManager]. */
    @SuppressLint("WakelockTimeout") // Held on purpose until onDestroy; see [wakeLock].
    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tryhearingaid:emergency-detector")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Connects the audio pipeline to Bluetooth wearables.
     *
     * 1. Starts classic (A2DP / HFP) discovery so already-paired hearing aids and
     *    earbuds are found. BLE scanning is deliberately *not* started here:
     *    [WearableBridgeImpl.startBleScan] currently connects to every BLE device it
     *    sees, so it needs a device filter / user-initiated pairing flow first.
     * 2. Follows the connected A2DP devices and routes processed audio to them,
     *    falling back to the default output when none is connected (Req 14.2).
     * 3. Forwards each new caption to display-capable wearables (Req 14.6).
     */
    private fun startWearableIntegration() {
        wearableBridge.setupClassicProfiles()

        serviceScope.launch {
            var currentRoute: AudioDevice = AudioDevice.Default
            wearableBridge.audioOutputAddresses
                .map { addresses -> AudioRoutePolicy.choose(addresses) }
                .distinctUntilChanged()
                .collect { route ->
                    // routeOutput() restarts a running engine, so only call it on a real change.
                    if (route != currentRoute) {
                        currentRoute = route
                        audioEngine.routeOutput(route)
                    }
                }
        }

        // Emergency sounds always go to a connected smartwatch, whatever the phone screen is
        // doing (Requirement 10.6).
        serviceScope.launch {
            emergencyDetector.emergencyEvents.collect { event ->
                // First, so the screen wakes at once (Requirement 10.2).
                emergencyAlertNotifier.show(event)
                try {
                    wearableBridge.sendAlert(
                        SoundAlert(
                            category = event.category,
                            confidence = event.confidence,
                            priority = AlertPriority.HIGH,
                            description = null,
                            detectedAtMs = event.detectedAtMs
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not forward emergency to wearable: ${e.message}")
                }
            }
        }

        val captionForwarder = CaptionForwarder()
        serviceScope.launch {
            captionEngine.captions.collect { segments ->
                captionForwarder.newSegments(segments).forEach { wearableBridge.sendCaptions(it) }
            }
        }
    }

    /**
     * Starts the foreground service with [FOREGROUND_SERVICE_TYPE_MICROPHONE] and
     * starts the audio engine.
     *
     * Callers should send [Intent.ACTION_DEFAULT] to start processing or pass a
     * custom action to control behaviour; the service is self-contained and does
     * not require intent extras for normal operation.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        // Promote to foreground before any audio work starts
        startForegroundWithMicrophoneType()

        // The controller starts the engine once headphones are connected, and stops it if they
        // are removed. Calling begin() again is harmless.
        amplifierController.begin()

        // Sticky so the OS restarts the service if it is killed with a non-null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        // Returning true enables onRebind if clients re-bind later
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind")
        super.onRebind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Stop emergency detector before the audio engine so any in-flight
        // detections are cleanly cancelled before pipeline teardown.
        emergencyDetector.stop()
        // The classifier is shared app-wide and cannot be reopened after close(), so it is only
        // switched off here (it is switched on again in onCreate) and freed when the process ends.
        soundClassifier.setEnabled(false)
        amplifierController.end()
        // Stop following wearable state. The bridge itself is an app-wide singleton
        // shared with the dispatcher, so it is not released here.
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Foreground notification helpers ───────────────────────────────────────

    /**
     * Creates the [NotificationChannel] for the foreground service notification.
     * Safe to call multiple times; the system ignores duplicate registrations.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Hearing Aid Processing",
            NotificationManager.IMPORTANCE_LOW          // Low importance: no sound/badge
        ).apply {
            description = "Keeps the hearing aid audio pipeline running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds and returns the foreground service notification.
     *
     * Tapping the notification opens [MainActivity].
     */
    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Hearing aid active")
            .setContentText("Audio processing is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)               // Cannot be dismissed by the user
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Calls [ServiceCompat.startForeground] with
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] on API 29+ and falls back
     * to the type-less overload on older APIs.
     */
    private fun startForegroundWithMicrophoneType() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
