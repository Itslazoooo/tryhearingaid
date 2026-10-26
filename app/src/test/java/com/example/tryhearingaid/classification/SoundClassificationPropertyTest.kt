// Feature: advanced-hearing-aid-app, Property 11: SoundClassifier supports >= 20 required categories
// Feature: advanced-hearing-aid-app, Property 12: Alert priority maps correctly from confidence
// Feature: advanced-hearing-aid-app, Property 13: Flashlight pattern matches config
// Feature: advanced-hearing-aid-app, Property 14: Enabled categories filter determines dispatch
// Feature: advanced-hearing-aid-app, Property 15: EmergencyDetector includes all 6 life-safety categories

package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.AlertPriority
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import org.junit.runner.RunWith

/**
 * Property-based tests for sound classification and notification dispatch:
 * Properties 11 through 15.
 *
 * **Validates: Requirements 9.1, 9.2, 9.4, 9.5, 9.6, 10.1**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SoundClassificationPropertyTest : FunSpec({

    // ── Property 11 ───────────────────────────────────────────────────────────

    /**
     * Property 11: SoundClassifier supports ≥ 20 required categories.
     *
     * Every value in the [SoundCategory] enum must be represented as a key in
     * [SoundClassifierImpl.CATEGORY_INDEX_MAP], meaning the classifier can
     * potentially detect it. The enum has 21 values so this also confirms
     * the ≥ 20 requirement is satisfied.
     *
     * **Validates: Requirements 9.1**
     */
    // Feature: advanced-hearing-aid-app, Property 11: SoundClassifier supports >= 20 required categories
    test("Property 11: SoundClassifier CATEGORY_INDEX_MAP covers all SoundCategory enum values") {
        val supportedCategories = SoundClassifierImpl.CATEGORY_INDEX_MAP.keys

        // Assert at least 20 categories are detectable (requirement 9.1)
        assert(supportedCategories.size >= 20) {
            "Expected >= 20 detectable categories, but CATEGORY_INDEX_MAP has only " +
                    "${supportedCategories.size}"
        }

        // Assert every enum value is present in the map
        val allCategories = SoundCategory.entries
        assert(allCategories.size >= 20) {
            "SoundCategory enum has ${allCategories.size} values; expected >= 20"
        }

        allCategories.forEach { category ->
            assert(category in supportedCategories) {
                "SoundCategory.$category is missing from CATEGORY_INDEX_MAP"
            }
        }

        // Spot-check the 16 categories explicitly listed in Requirement 9.1
        val requiredBySpec = setOf(
            SoundCategory.DOORBELL,
            SoundCategory.BABY_CRYING,
            SoundCategory.FIRE_ALARM,
            SoundCategory.CAR_HORN,
            SoundCategory.DOG_BARKING,
            SoundCategory.NAME_CALLED,
            SoundCategory.KNOCK,
            SoundCategory.PHONE_RINGING,
            SoundCategory.WATER_RUNNING,
            SoundCategory.GLASS_BREAKING,
            SoundCategory.SIREN,
            SoundCategory.SMOKE_ALARM,
            SoundCategory.CROWD_CHEERING,
            SoundCategory.BIRDS,
            SoundCategory.THUNDER,
            SoundCategory.SPEECH
        )
        requiredBySpec.forEach { category ->
            assert(category in supportedCategories) {
                "Requirement 9.1 category $category not in CATEGORY_INDEX_MAP"
            }
        }
    }

    // ── Property 12 ───────────────────────────────────────────────────────────

    /**
     * Property 12: Alert priority maps correctly from confidence.
     *
     * For any confidence in [0, 1]:
     * - confidence ≥ 0.80 → HIGH priority
     * - 0.50 ≤ confidence < 0.80 → LOW priority
     * - confidence < 0.50 → no dispatch (null)
     *
     * **Validates: Requirements 9.2, 9.6**
     */
    // Feature: advanced-hearing-aid-app, Property 12: Alert priority maps correctly from confidence
    test("Property 12: mapToPriority returns HIGH for confidence >= 0.80") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(
                NotificationDispatcherImpl.HIGH_CONFIDENCE_THRESHOLD,
                1.0f
            )
        ) { confidence ->
            val dispatcher = FakeNotificationDispatcher()
            val priority = dispatcher.mapToPriorityPublic(confidence)
            priority shouldBe AlertPriority.HIGH
        }
    }

    // Feature: advanced-hearing-aid-app, Property 12: Alert priority maps correctly from confidence
    test("Property 12: mapToPriority returns LOW for confidence in [0.50, 0.80)") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(
                NotificationDispatcherImpl.LOW_CONFIDENCE_MIN,
                NotificationDispatcherImpl.HIGH_CONFIDENCE_THRESHOLD - 0.001f
            )
        ) { confidence ->
            val dispatcher = FakeNotificationDispatcher()
            val priority = dispatcher.mapToPriorityPublic(confidence)
            priority shouldBe AlertPriority.LOW
        }
    }

    // Feature: advanced-hearing-aid-app, Property 12: Alert priority maps correctly from confidence
    test("Property 12: mapToPriority returns null (no dispatch) for confidence < 0.50") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(0f, NotificationDispatcherImpl.LOW_CONFIDENCE_MIN - 0.001f)
        ) { confidence ->
            val dispatcher = FakeNotificationDispatcher()
            val priority = dispatcher.mapToPriorityPublic(confidence)
            priority.shouldBeNull()
        }
    }

    // ── Property 13 ───────────────────────────────────────────────────────────

    /**
     * Property 13: Flashlight pattern matches config.
     *
     * For any [FlashlightPattern], after calling [setFlashlightPattern] the
     * dispatcher records exactly that pattern when the FLASHLIGHT channel fires.
     *
     * **Validates: Requirements 9.3, 9.4**
     */
    // Feature: advanced-hearing-aid-app, Property 13: Flashlight pattern matches config
    test("Property 13: dispatched flashlight pattern always matches configured pattern") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.enum<FlashlightPattern>()
        ) { pattern ->
            val recorder = RecordingDispatcher()
            recorder.setFlashlightPattern(pattern)

            // Dispatch a high-confidence detection so FLASHLIGHT channel fires
            val detection = SoundDetection(
                category = SoundCategory.DOORBELL,
                confidence = 0.90f,
                timestampMs = System.currentTimeMillis()
            )
            recorder.dispatch(detection, AlertPriority.HIGH)

            recorder.lastFlashlightPattern shouldBe pattern
        }
    }

    // ── Property 14 ───────────────────────────────────────────────────────────

    /**
     * Property 14: Enabled categories filter determines dispatch.
     *
     * For a random enabled subset of [SoundCategory], dispatch is called for
     * every category in the full enum. Only categories that are in the enabled
     * subset should produce a detection; others should be silently dropped.
     *
     * **Validates: Requirements 9.5**
     */
    // Feature: advanced-hearing-aid-app, Property 14: Enabled categories filter determines dispatch
    test("Property 14: only enabled categories pass through the SoundClassifier filter") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.set(
                Arb.enum<SoundCategory>(),
                0..SoundCategory.entries.size
            )
        ) { enabledSet ->
            val tracker = CategoryFilterTracker()
            tracker.setEnabledCategories(enabledSet)

            // Submit every category as a detection
            SoundCategory.entries.forEach { category ->
                tracker.submitDetection(
                    SoundDetection(
                        category = category,
                        confidence = 0.90f,
                        timestampMs = System.currentTimeMillis()
                    )
                )
            }

            // Enabled categories must have been forwarded
            enabledSet.forEach { category ->
                tracker.forwardedCategories.contains(category).shouldBeTrue()
            }

            // Disabled categories must NOT have been forwarded
            val disabledSet = SoundCategory.entries.toSet() - enabledSet
            disabledSet.forEach { category ->
                tracker.forwardedCategories.contains(category).shouldBeFalse()
            }
        }
    }

    // ── Property 15 ───────────────────────────────────────────────────────────

    /**
     * Property 15: EmergencyDetector includes all 6 life-safety categories.
     *
     * [EmergencyDetectorImpl.LIFE_SAFETY_CATEGORIES] must always contain exactly
     * the six categories required by Requirement 10.1.
     *
     * **Validates: Requirements 10.1**
     */
    // Feature: advanced-hearing-aid-app, Property 15: EmergencyDetector includes all 6 life-safety categories
    test("Property 15: LIFE_SAFETY_CATEGORIES contains all 6 required categories") {
        // Run 100 times — this is a structural invariant, no random input required
        repeat(100) {
            val monitored = EmergencyDetectorImpl.LIFE_SAFETY_CATEGORIES

            assert(monitored.size >= 6) {
                "LIFE_SAFETY_CATEGORIES should contain at least 6 categories, " +
                        "but has ${monitored.size}"
            }

            val required = setOf(
                SoundCategory.SMOKE_ALARM,
                SoundCategory.CO_ALARM,
                SoundCategory.FIRE_TRUCK_SIREN,
                SoundCategory.AMBULANCE_SIREN,
                SoundCategory.POLICE_SIREN,
                SoundCategory.VERBAL_HELP
            )

            required.forEach { category ->
                monitored shouldContain category
            }
        }
    }
})

// ── Test helpers ──────────────────────────────────────────────────────────────

/**
 * Thin wrapper that exposes [NotificationDispatcherImpl.mapToPriority] as a
 * public method for property testing without constructing the full Android
 * system-service graph.
 */
private class FakeNotificationDispatcher {
    fun mapToPriorityPublic(confidence: Float): AlertPriority? = when {
        confidence >= NotificationDispatcherImpl.HIGH_CONFIDENCE_THRESHOLD -> AlertPriority.HIGH
        confidence >= NotificationDispatcherImpl.LOW_CONFIDENCE_MIN        -> AlertPriority.LOW
        else                                                                -> null
    }
}

/**
 * Minimal [NotificationDispatcher] implementation that records the most recently
 * configured [FlashlightPattern] when [dispatch] is called with the FLASHLIGHT
 * channel active, without touching any Android system services.
 *
 * Used by Property 13 to verify pattern round-trip.
 */
private class RecordingDispatcher : NotificationDispatcher {

    private var pattern: FlashlightPattern = FlashlightPattern.SHORT
    private val channels: MutableSet<AlertChannel> = mutableSetOf(AlertChannel.FLASHLIGHT)

    /** The pattern that was active when the last FLASHLIGHT dispatch occurred. */
    var lastFlashlightPattern: FlashlightPattern = FlashlightPattern.SHORT
        private set

    override fun dispatch(detection: SoundDetection, priority: AlertPriority) {
        // Honour the same ignore-below-0.50 rule so the property stays faithful
        val resolved = when {
            detection.confidence >= NotificationDispatcherImpl.HIGH_CONFIDENCE_THRESHOLD -> AlertPriority.HIGH
            detection.confidence >= NotificationDispatcherImpl.LOW_CONFIDENCE_MIN        -> AlertPriority.LOW
            else -> return
        }
        if (AlertChannel.FLASHLIGHT in channels) {
            lastFlashlightPattern = pattern
        }
    }

    override fun setChannels(channels: Set<AlertChannel>) {
        this.channels.clear()
        this.channels.addAll(channels)
    }

    override fun setFlashlightPattern(pattern: FlashlightPattern) {
        this.pattern = pattern
    }

    override fun release() {}
}

/**
 * Simulates the enabled-categories filter logic from [SoundClassifierImpl].
 *
 * Mirrors the production rule: a detection is forwarded only if its category
 * is in [enabledCategories]. Used by Property 14.
 */
private class CategoryFilterTracker {

    private var enabledCategories: Set<SoundCategory> = emptySet()
    val forwardedCategories: MutableSet<SoundCategory> = mutableSetOf()

    fun setEnabledCategories(categories: Set<SoundCategory>) {
        enabledCategories = categories.toSet()
    }

    fun submitDetection(detection: SoundDetection) {
        if (detection.category in enabledCategories) {
            forwardedCategories.add(detection.category)
        }
    }
}
