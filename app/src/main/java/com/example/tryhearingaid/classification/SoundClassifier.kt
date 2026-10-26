package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import kotlinx.coroutines.flow.SharedFlow

/**
 * Classifies environmental sounds into app-level [SoundCategory] values.
 *
 * Implemented by [SoundClassifierImpl] using a YAMNet TFLite model that maps
 * 521 AudioSet class indices to the 21 [SoundCategory] enum values.
 *
 * Consumers collect [detections] to receive classified sound events.
 * The filter applied via [setEnabledCategories] silently drops detections
 * for disabled categories before they reach the flow.
 *
 * Requirements: 9.1, 9.5
 */
interface SoundClassifier {

    /**
     * Emits a [SoundDetection] each time a sound is classified above the
     * minimum confidence threshold **and** its category is in the enabled set.
     */
    val detections: SharedFlow<SoundDetection>

    /**
     * Replace the set of categories that will be forwarded to [detections].
     * Detections for categories absent from [categories] are silently dropped.
     * An empty set means no detections are forwarded.
     *
     * Requirement 9.5
     */
    fun setEnabledCategories(categories: Set<SoundCategory>)

    /**
     * Start or stop the classification coroutine.
     *
     * When [enabled] is `false` the background coroutine is cancelled and no
     * further [detections] are emitted until [setEnabled](true) is called again.
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Submit a 16 kHz mono PCM audio frame for classification.
     *
     * [samples] should contain exactly (or approximately) 15 600 samples
     * (0.975 s at 16 kHz) — the window size expected by YAMNet.
     * Shorter frames are zero-padded; longer frames are truncated internally.
     *
     * This method is non-blocking: it enqueues the frame for processing on
     * the background coroutine.
     */
    fun submitAudioFrame(samples: ShortArray)
}
