package com.example.tryhearingaid.service

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
import com.example.tryhearingaid.audio.VoiceIsolatorImpl
import com.example.tryhearingaid.captions.CaptionEngine
import com.example.tryhearingaid.classification.EmergencyDetectorImpl
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

    /**
     * Owned [AudioEngineImpl] instance. Created when the service is created and
     * released when it is destroyed.
     */
    private val audioEngine = AudioEngineImpl()

    /**
     * [DirectionalFilterImpl] wired as the first stage in the pipeline (before NoiseReducer).
     * On single-mic devices it acts as a transparent pass-through.
     * Lazily initialised in [onCreate] once a Context is available.
     */
    private lateinit var directionalFilter: DirectionalFilterImpl

    /**
     * NoiseReducerImpl wired into the pipeline after DirectionalFilter.
     * Lazily initialised in [onCreate] once a Context is available.
     */
    private lateinit var noiseReducer: NoiseReducerImpl

    /**
     * VoiceIsolatorImpl wired into the pipeline after [noiseReducer].
     * Lazily initialised in [onCreate] once a Context is available.
     */
    private lateinit var voiceIsolator: VoiceIsolatorImpl

    /**
     * [EqualizerImpl] wired as the last stage in the pipeline (after VoiceIsolator),
     * applying per-band IIR gain before the adaptive gain and AudioTrack output.
     * Lazily initialised in [onCreate].
     */
    private lateinit var equalizer: EqualizerImpl

    /**
     * [SoundClassifierImpl] that runs YAMNet classification on audio frames.
     * Lazily initialised in [onCreate] once a Context is available.
     */
    private lateinit var soundClassifier: SoundClassifierImpl

    /**
     * [EmergencyDetectorImpl] subscribed to [soundClassifier]'s detections flow.
     *
     * Started in [onCreate] and stopped only in [onDestroy] so that emergency
     * detection remains active even when other features are paused.
     * Requirement 10.5.
     */
    private lateinit var emergencyDetector: EmergencyDetectorImpl

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

        // Instantiate and wire all processing stages into the pipeline in order:
        // DirectionalFilter → NoiseReducer → VoiceIsolator → Equalizer
        // (Adaptive gain and AudioTrack output are applied after all stages.)
        directionalFilter = DirectionalFilterImpl(this)
        noiseReducer = NoiseReducerImpl(this)
        voiceIsolator = VoiceIsolatorImpl(this)
        equalizer = EqualizerImpl()

        audioEngine.addProcessingStage(directionalFilter)
        audioEngine.addProcessingStage(noiseReducer)
        audioEngine.addProcessingStage(voiceIsolator)
        audioEngine.addProcessingStage(equalizer)

        // Instantiate SoundClassifier and EmergencyDetector.
        // EmergencyDetector subscribes to SoundClassifier.detections and must
        // remain active for the entire service lifetime (Requirement 10.5).
        soundClassifier = SoundClassifierImpl(this)
        emergencyDetector = EmergencyDetectorImpl(soundClassifier)
        emergencyDetector.start()
        Log.d(TAG, "EmergencyDetector started — always-on subscription active.")

        startWearableIntegration()
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

        // Start the engine if it is idle
        if (audioEngine.state.value == AudioEngineState.IDLE) {
            audioEngine.start(AudioConfig())
        }

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
        soundClassifier.close()
        audioEngine.stop()
        // Stop following wearable state. The bridge itself is an app-wide singleton
        // shared with the dispatcher, so it is not released here.
        serviceScope.cancel()
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
