package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.AlertPriority
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundDetection

/**
 * Routes sound-detection alerts to the correct output channels based on user preferences.
 *
 * Implementations are responsible for:
 * - Mapping [SoundDetection.confidence] to [AlertPriority]
 *   (HIGH ≥ 0.80, LOW 0.50–0.79, ignored < 0.50).
 * - Dispatching the alert to each enabled [AlertChannel].
 * - Applying the configured [FlashlightPattern] when the FLASHLIGHT channel is active.
 *
 * Requirements: 9.2, 9.3, 9.4, 9.6
 * Properties 12, 13
 */
interface NotificationDispatcher {

    /**
     * Map [detection.confidence] to a priority and dispatch to all enabled channels.
     *
     * - confidence ≥ 0.80 → [AlertPriority.HIGH]
     * - 0.50 ≤ confidence < 0.80 → [AlertPriority.LOW]
     * - confidence < 0.50 → silently ignored, no dispatch
     *
     * Requirement 9.6
     */
    fun dispatch(detection: SoundDetection, priority: AlertPriority)

    /**
     * Replace the set of active [AlertChannel]s.
     * Only channels in [channels] will receive alerts on subsequent [dispatch] calls.
     *
     * Requirement 9.3
     */
    fun setChannels(channels: Set<AlertChannel>)

    /**
     * Set the [FlashlightPattern] used for FLASHLIGHT channel alerts.
     *
     * Requirement 9.4
     */
    fun setFlashlightPattern(pattern: FlashlightPattern)

    /**
     * Release any background resources held by this dispatcher (coroutine scopes, etc.).
     * Call this when the owning service is destroyed.
     */
    fun release()
}
