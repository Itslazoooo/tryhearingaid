package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.EmergencyEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors [SoundClassifier.detections] for life-safety sounds and emits an
 * [EmergencyEvent] whenever a life-safety category is detected with confidence
 * ≥ 0.85.
 *
 * The six monitored categories are:
 *   - SMOKE_ALARM
 *   - CO_ALARM
 *   - FIRE_TRUCK_SIREN
 *   - AMBULANCE_SIREN
 *   - POLICE_SIREN
 *   - VERBAL_HELP
 *
 * The detector **must remain active** even when other features are paused.
 * [AudioProcessingService] subscribes to [emergencyEvents] in its own
 * `SupervisorJob`-backed scope and never cancels this subscription as long as
 * the service is alive.
 *
 * Requirements: 10.1, 10.2, 10.5
 * Property 15: monitored set always contains all 6 life-safety categories.
 */
interface EmergencyDetector {

    /**
     * Emits an [EmergencyEvent] each time a life-safety sound is detected with
     * confidence ≥ [EMERGENCY_CONFIDENCE_THRESHOLD].
     *
     * This is a hot [SharedFlow] (replay = 1) so new subscribers immediately
     * receive the most recent emergency event, if any.
     */
    val emergencyEvents: SharedFlow<EmergencyEvent>

    /**
     * `true` while the detector is subscribed to the upstream
     * [SoundClassifier.detections] flow and can emit events.
     * Set to `false` only when [stop] has been called.
     */
    val isActive: StateFlow<Boolean>

    /**
     * Start the always-on subscription to [SoundClassifier.detections].
     * Safe to call multiple times — subsequent calls are no-ops when already active.
     */
    fun start()

    /**
     * Stop the subscription and set [isActive] to `false`.
     * Intended only for service teardown; not called during normal paused states.
     */
    fun stop()
}
