package com.example.tryhearingaid.audio

import com.example.tryhearingaid.data.Audiogram
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [EqualizerImpl].
 *
 * Validates:
 * - Requirements 5.1, 5.2, 5.3, 5.4, 12.3, 12.4
 * - Requirements 6.1, 6.3
 */
class EqualizerImplTest {

    private lateinit var equalizer: EqualizerImpl

    companion object {
        /** Tolerance for float comparisons. */
        private const val DELTA = 0.01f
    }

    @Before
    fun setUp() {
        equalizer = EqualizerImpl()
    }

    // ── Band structure ────────────────────────────────────────────────────────

    @Test
    fun `8 bands at correct frequencies`() {
        val expectedFrequencies = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
        val actualBands = equalizer.bands.value

        assertEquals("Should have exactly 8 bands", 8, actualBands.size)
        actualBands.forEachIndexed { i, band ->
            assertEquals(
                "Band $i should be centered at ${expectedFrequencies[i]} Hz",
                expectedFrequencies[i],
                band.centerHz
            )
        }
    }

    @Test
    fun `default bands have 0 dB gain`() {
        val bands = equalizer.bands.value
        bands.forEachIndexed { i, band ->
            assertEquals(
                "Default band $i gain should be 0 dB",
                0f, band.gainDb, DELTA
            )
        }
    }

    // ── Gain clamping ─────────────────────────────────────────────────────────

    @Test
    fun `setBandGain clamps to min value`() {
        equalizer.setBandGain(0, -50f)  // Request below min
        assertEquals(
            "Gain should be clamped to ${EqualizerImpl.GAIN_MIN_DB} dB",
            EqualizerImpl.GAIN_MIN_DB, equalizer.bands.value[0].gainDb, DELTA
        )
    }

    @Test
    fun `setBandGain clamps to max value`() {
        equalizer.setBandGain(0, 100f)  // Request above max
        assertEquals(
            "Gain should be clamped to ${EqualizerImpl.GAIN_MAX_DB} dB",
            EqualizerImpl.GAIN_MAX_DB, equalizer.bands.value[0].gainDb, DELTA
        )
    }

    @Test
    fun `setBandGain within valid range is accepted`() {
        equalizer.setBandGain(2, 10f)
        assertEquals(
            "Gain within valid range should be accepted as-is",
            10f, equalizer.bands.value[2].gainDb, DELTA
        )
    }

    @Test
    fun `setBandGain with invalid band index is ignored`() {
        val originalBands = equalizer.bands.value.toList()
        equalizer.setBandGain(-1, 10f)
        assertEquals("Negative band index should be ignored", originalBands, equalizer.bands.value)

        equalizer.setBandGain(100, 10f)
        assertEquals("Out-of-range band index should be ignored", originalBands, equalizer.bands.value)
    }

    // ── applyAudiogram ────────────────────────────────────────────────────────

    @Test
    fun `applyAudiogram with 0 dBHL produces 0 dB gain`() {
        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar = mapOf(250 to 0, 500 to 0, 1000 to 0, 2000 to 0, 3000 to 0, 4000 to 0, 6000 to 0, 8000 to 0),
            rightEar = mapOf(),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        equalizer.bands.value.forEachIndexed { i, band ->
            assertEquals(
                "Band $i: 0 dBHL → 0 dB gain",
                0f, band.gainDb, DELTA
            )
        }
    }

    @Test
    fun `applyAudiogram with 80 dBHL produces 40 dB gain`() {
        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar = mapOf(250 to 80, 500 to 80, 1000 to 80, 2000 to 80, 3000 to 80, 4000 to 80, 6000 to 80, 8000 to 80),
            rightEar = mapOf(),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        equalizer.bands.value.forEachIndexed { i, band ->
            assertEquals(
                "Band $i: 80 dBHL → 40 dB gain (max boost)",
                40f, band.gainDb, DELTA
            )
        }
    }

    /**
     * Task 6.3 key assertion: verify applyAudiogram produces gains proportional to thresholds.
     *
     * Linear mapping: gain = threshold(dBHL) × (40 / 80) = threshold × 0.5
     * This ensures bands with higher hearing-loss thresholds receive proportionally higher gain.
     *
     * Requirements: 5.4, 12.3
     */
    @Test
    fun `applyAudiogram produces gains proportional to thresholds`() {
        // Test a range of known threshold → expected gain mappings
        val testCases = listOf(
            0   to 0f,    // 0 dBHL  → 0 dB gain
            20  to 10f,   // 20 dBHL → 10 dB gain
            40  to 20f,   // 40 dBHL → 20 dB gain
            60  to 30f,   // 60 dBHL → 30 dB gain
            80  to 40f    // 80 dBHL → 40 dB gain (max)
        )

        testCases.forEach { (thresholdDbHl, expectedGainDb) ->
            val audiogram = Audiogram(
                profileId = UUID.randomUUID(),
                leftEar = mapOf(250 to thresholdDbHl),
                rightEar = mapOf(),
                recordedAt = Instant.now()
            )
            equalizer.applyAudiogram(audiogram)
            assertEquals(
                "Threshold $thresholdDbHl dBHL should map to $expectedGainDb dB gain",
                expectedGainDb, equalizer.bands.value[0].gainDb, DELTA
            )
        }
    }

    @Test
    fun `applyAudiogram higher thresholds produce higher or equal gains`() {
        // Confirm monotonicity: sorting thresholds ascending should yield ascending gains
        val freqs = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
        val ascendingThresholds = listOf(0, 10, 20, 30, 40, 50, 60, 70)

        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar = freqs.zip(ascendingThresholds).toMap(),
            rightEar = mapOf(),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        val bands = equalizer.bands.value

        for (i in 0 until bands.size - 1) {
            assertTrue(
                "Band $i gain (${bands[i].gainDb}) should be ≤ band ${i+1} gain (${bands[i+1].gainDb})",
                bands[i].gainDb <= bands[i + 1].gainDb + DELTA
            )
        }
    }

    @Test
    fun `applyAudiogram averages left and right ear thresholds`() {
        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar  = mapOf(250 to 20, 500 to 30),
            rightEar = mapOf(250 to 40, 500 to 50, 1000 to 80),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        val bands = equalizer.bands.value

        // 250 Hz: (20 + 40) / 2 = 30 dBHL → 15 dB gain
        assertEquals("250 Hz: avg(20,40)=30 dBHL → 15 dB", 15f, bands[0].gainDb, DELTA)
        // 500 Hz: (30 + 50) / 2 = 40 dBHL → 20 dB gain
        assertEquals("500 Hz: avg(30,50)=40 dBHL → 20 dB", 20f, bands[1].gainDb, DELTA)
        // 1000 Hz: only right ear 80 dBHL → 40 dB gain
        assertEquals("1000 Hz: right-ear-only 80 dBHL → 40 dB", 40f, bands[2].gainDb, DELTA)
    }

    @Test
    fun `applyAudiogram falls back to 0 dBHL for missing frequencies`() {
        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar  = mapOf(250 to 20),
            rightEar = mapOf(),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        val bands = equalizer.bands.value

        assertEquals("250 Hz: 20 dBHL → 10 dB", 10f, bands[0].gainDb, DELTA)
        for (i in 1..7) {
            assertEquals("Band $i: missing freq → 0 dBHL → 0 dB", 0f, bands[i].gainDb, DELTA)
        }
    }

    @Test
    fun `applyAudiogram clamps extreme gains to valid range`() {
        val audiogram = Audiogram(
            profileId = UUID.randomUUID(),
            leftEar  = mapOf(250 to 200, 500 to -50),  // extreme values
            rightEar = mapOf(),
            recordedAt = Instant.now()
        )
        equalizer.applyAudiogram(audiogram)
        val bands = equalizer.bands.value

        assertEquals("200 dBHL should clamp to max gain", EqualizerImpl.GAIN_MAX_DB, bands[0].gainDb, DELTA)
        assertEquals("-50 dBHL should clamp to min gain", EqualizerImpl.GAIN_MIN_DB, bands[1].gainDb, DELTA)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all gains to 0 dB`() {
        equalizer.setBandGain(0, 15f)
        equalizer.setBandGain(1, -10f)
        equalizer.reset()

        equalizer.bands.value.forEachIndexed { i, band ->
            assertEquals("Band $i should be 0 dB after reset", 0f, band.gainDb, DELTA)
        }
    }

    // ── Processing ────────────────────────────────────────────────────────────

    @Test
    fun `disabled stage passes through buffer unchanged`() {
        val testBuffer = ShortArray(160) { 1000 }
        equalizer.setBandGain(0, 20f)
        equalizer.setEnabled(false)

        val result = equalizer.process(testBuffer.clone(), 16000, 1)
        assertTrue("Disabled equalizer should pass through unmodified", result.contentEquals(testBuffer))
    }

    @Test
    fun `process preserves buffer size for mono and stereo`() {
        val monoBuffer   = ShortArray(320) { 500 }
        val stereoBuffer = ShortArray(640) { 500 }

        assertEquals("Mono size preserved",   320, equalizer.process(monoBuffer, 16000, 1).size)
        assertEquals("Stereo size preserved", 640, equalizer.process(stereoBuffer, 16000, 2).size)
    }

    @Test
    fun `band gain changes are reflected in StateFlow immediately`() = runTest {
        equalizer.setBandGain(0, 15f)
        assertEquals("StateFlow should reflect band gain immediately", 15f, equalizer.bands.value[0].gainDb, DELTA)
    }
}
