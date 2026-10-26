package com.example.tryhearingaid.audio

// Feature: advanced-hearing-aid-app, Property 1: Gain values are always clamped to the valid range

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlin.math.roundToInt
import org.junit.runner.RunWith

/**
 * Property 1: Gain values are always clamped to the valid range [0 dB, 40 dB]
 * and quantized to the nearest 1 dB increment.
 *
 * **Validates: Requirements 1.2, 6.3**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class AudioEngineGainPropertyTest : FreeSpec({

    // AudioEngineImpl does not require start() to be called for setGain/currentGain —
    // the setGain logic is pure computation that updates _currentGain before any
    // AudioRecord or AudioTrack is touched. This allows pure JVM unit testing.

    "Property 1: Gain values are always clamped to the valid range" {
        // Feature: advanced-hearing-aid-app, Property 1: Gain values are always clamped to the valid range
        // For any arbitrary float in [-200, 200], after calling setGain() the applied
        // currentGain must be within [0f, 40f] AND rounded to the nearest 1 dB.

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(-200f, 200f)
        ) { arbitraryGain ->
            val engine = AudioEngineImpl()
            engine.setGain(arbitraryGain)

            val appliedGain = engine.currentGain

            // 1. Applied gain must be within [0f, 40f]
            appliedGain shouldBeGreaterThanOrEqualTo AudioEngineImpl.GAIN_MIN_DB
            appliedGain shouldBeLessThanOrEqualTo AudioEngineImpl.GAIN_MAX_DB

            // 2. Applied gain must be rounded to the nearest 1 dB
            //    i.e. appliedGain == appliedGain.roundToInt().toFloat()
            val rounded = appliedGain.roundToInt().toFloat()
            appliedGain shouldBe rounded
        }
    }
})
