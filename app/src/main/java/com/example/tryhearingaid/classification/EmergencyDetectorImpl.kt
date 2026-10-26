package com.example.tryhearingaid.classification

import android.util.Log
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.SoundCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [EmergencyDetector].
 *
 * Subscribes to [SoundClassifier.detections] on a dedicated [SupervisorJob]-backed
 * coroutine scope ([Dispatchers.Default]) and re-emits any detection whose
 * category is in [LIFE_SAFETY_CATEGORIES] **and** whose confidence is ≥
 * [EMERGENCY_CONFIDENCE_THRESHOLD] as an [EmergencyEvent].
 *
 * ## Always-on guarantee
 * [AudioProcessingService] calls [start] once in `onCreate` and only calls [stop]
 * in `onDestroy`.  The internal scope uses [SupervisorJob] so a failure in any
 * other child job does not cancel the emergency-detection subscription.
 *
 * ## Thread-safety
 * [start] and [stop] are safe to call from any thread. The [Job] reference is
 * guarded by `@Volatile`; rapid start/stop cycles are handled idempotently.
 *
 * Requirements: 10.1, 10.2, 10.5
 * Property 15: [LIFE_SAFETY_CATEGORIES] always contains all 6 required categories.
 *
 * @param soundClassifier The upstream classifier whose [SoundClassifier.detections]
 *                        flow this detector subscribes to.
 */
@Singleton
class EmergencyDetectorImpl @Inject constructor(
    private val soundClassifier: SoundClassifier
) : EmergencyDetector {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "EmergencyDetectorImpl"

        /**
         * Minimum confidence required to emit an [EmergencyEvent].
         * Requirement 10.2: 85% confidence threshold.
         */
        const val EMERGENCY_CONFIDENCE_THRESHOLD = 0.85f

        /**
         * The six life-safety categories monitored by this detector.
         *
         * Property 15: this set must always contain all 6 categories below.
         * Requirement 10.1.
         */
        val LIFE_SAFETY_CATEGORIES: Set<SoundCategory> = setOf(
            SoundCategory.SMOKE_ALARM,
            SoundCategory.CO_ALARM,
            SoundCategory.FIRE_TRUCK_SIREN,
            SoundCategory.AMBULANCE_SIREN,
            SoundCategory.POLICE_SIREN,
            SoundCategory.VERBAL_HELP
        )
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    /**
     * Replay = 1 so new subscribers (e.g. after screen rotation) immediately
     * receive the most recent emergency event if one is in progress.
     */
    private val _emergencyEvents = MutableSharedFlow<EmergencyEvent>(replay = 1)
    override val emergencyEvents: SharedFlow<EmergencyEvent> = _emergencyEvents.asSharedFlow()

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // ── Coroutine scope ───────────────────────────────────────────────────────

    /**
     * A long-lived scope backed by [SupervisorJob] so failures in sibling
     * coroutines do not cancel the emergency subscription.
     *
     * The scope lives as long as this singleton; it is cancelled only in [stop].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Reference to the active subscription job; non-null while [isActive] is true. */
    @Volatile
    private var subscriptionJob: Job? = null

    // ── EmergencyDetector API ─────────────────────────────────────────────────

    /**
     * Start the always-on subscription to [SoundClassifier.detections].
     *
     * If already active this is a no-op.  The subscription filters detections to
     * [LIFE_SAFETY_CATEGORIES] with confidence ≥ [EMERGENCY_CONFIDENCE_THRESHOLD]
     * and emits matching ones as [EmergencyEvent]s.
     *
     * Requirement 10.5: remains active even when other features are paused.
     */
    override fun start() {
        if (_isActive.value) {
            Log.d(TAG, "start() called but detector is already active — ignoring.")
            return
        }
        Log.i(TAG, "Starting emergency detector subscription.")

        subscriptionJob = scope.launch {
            soundClassifier.detections.collect { detection ->
                if (detection.category in LIFE_SAFETY_CATEGORIES &&
                    detection.confidence >= EMERGENCY_CONFIDENCE_THRESHOLD
                ) {
                    val event = EmergencyEvent(
                        category = detection.category,
                        confidence = detection.confidence,
                        detectedAtMs = detection.timestampMs
                    )
                    Log.w(TAG, "Emergency detected: ${detection.category} " +
                            "@ confidence=${detection.confidence}")
                    _emergencyEvents.emit(event)
                }
            }
        }

        _isActive.value = true
    }

    /**
     * Stop the subscription.
     *
     * Only call this during service teardown ([AudioProcessingService.onDestroy]).
     * Normal feature-pause flows must NOT call this method so that emergency
     * detection remains active as required by Requirement 10.5.
     */
    override fun stop() {
        Log.i(TAG, "Stopping emergency detector.")
        subscriptionJob?.cancel()
        subscriptionJob = null
        _isActive.value = false
        scope.cancel()
    }
}
