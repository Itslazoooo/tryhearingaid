package com.example.tryhearingaid.audio

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DirectionalFilterImpl].
 *
 * Task 6.3 key assertions:
 *   1. A single-mic (or no-mic) device sets [DirectionalFilter.isAvailable] to `false`.
 *   2. [EqualizerImpl.applyAudiogram] produces gains proportional to thresholds
 *      (covered in EqualizerImplTest — see that file for those assertions).
 *
 * Validates Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.4, 5.5
 */
class DirectionalFilterImplTest {

    private lateinit var directionalFilter: DirectionalFilterImpl
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mockk<Context>(relaxed = true)
        directionalFilter = DirectionalFilterImpl(mockContext)
    }

    // ── Task 6.3 key assertion 1: single-mic → isAvailable = false ───────────

    /**
     * When the device has fewer than 2 microphones (or mic enumeration fails),
     * [DirectionalFilter.isAvailable] must be `false` and the filter must act as a pass-through.
     *
     * The mock context returns null / empty responses from AudioManager, so the
     * implementation falls back to false.
     *
     * Requirements: 4.3
     */
    @Test
    fun `single-mic device sets isAvailable to false`() {
        // mockk returns null/empty for all AudioManager calls by default.
        // DirectionalFilterImpl detects zero mics → isAvailable must be false.
        assertFalse(
            "Single-mic (or no-mic) device should have isAvailable = false",
            directionalFilter.isAvailable.value
        )
    }

    @Test
    fun `when isAvailable is false beamforming process acts as pass-through`() {
        // isAvailable is false on the mock device, so process() must return the buffer unchanged.
        assertFalse("Pre-condition: isAvailable should be false", directionalFilter.isAvailable.value)

        val input = ShortArray(320) { it.toShort() }
        val output = directionalFilter.process(input.clone(), 16000, 2)

        assertTrue(
            "Pass-through: output should equal input when isAvailable=false",
            output.contentEquals(input)
        )
    }

    @Test
    fun `setDirection on unavailable device is a silent no-op`() {
        // Should never throw even though beamforming is unavailable.
        assertFalse("Pre-condition: isAvailable should be false", directionalFilter.isAvailable.value)

        directionalFilter.setDirection(AudioDirection.Front)
        directionalFilter.setDirection(AudioDirection.Left)
        directionalFilter.setDirection(AudioDirection.Right)
        directionalFilter.setDirection(AudioDirection.Rear)
        directionalFilter.setDirection(AudioDirection.Custom(45f))
        // Reaching here without exception means the no-op contract holds.
        assertTrue("setDirection must not crash on single-mic device", true)
    }

    // ── Disabled stage ────────────────────────────────────────────────────────

    @Test
    fun `disabled stage passes through buffer unchanged`() {
        directionalFilter.setEnabled(false)

        val input = ShortArray(320) { 100 }
        val output = directionalFilter.process(input.clone(), 16000, 2)

        assertTrue("Disabled filter should pass through unchanged", output.contentEquals(input))
    }

    // ── Mono input pass-through ───────────────────────────────────────────────

    @Test
    fun `mono input always passes through unchanged`() {
        // Beamforming requires stereo; mono must be passed through regardless.
        val mono = ShortArray(160) { 100 }
        val output = directionalFilter.process(mono.clone(), 16000, 1)

        assertTrue("Mono input should pass through unchanged", output.contentEquals(mono))
    }

    // ── Buffer size preservation ──────────────────────────────────────────────

    @Test
    fun `process returns buffer of same size`() {
        val input = ShortArray(320) { 100 }
        val output = directionalFilter.process(input, 16000, 2)
        assertEquals("Output buffer should be same size as input", input.size, output.size)
    }

    @Test
    fun `process with empty buffer returns empty buffer`() {
        val empty = ShortArray(0)
        val output = directionalFilter.process(empty, 16000, 2)
        assertEquals("Empty buffer should be returned as-is", 0, output.size)
    }

    @Test
    fun `process can be called multiple times with different buffer sizes`() {
        val out1 = directionalFilter.process(ShortArray(160) { 50 }, 16000, 2)
        val out2 = directionalFilter.process(ShortArray(320) { 100 }, 16000, 2)
        val out3 = directionalFilter.process(ShortArray(640) { 200 }, 16000, 2)

        assertEquals("Small buffer size preserved", 160, out1.size)
        assertEquals("Medium buffer size preserved", 320, out2.size)
        assertEquals("Large buffer size preserved", 640, out3.size)
    }

    @Test
    fun `process handles minimum stereo and large buffers`() {
        val minOutput  = directionalFilter.process(ShortArray(2) { 100 }, 16000, 2)
        val largeInput = ShortArray(16000 * 2) { 100 }
        val largeOutput = directionalFilter.process(largeInput, 16000, 2)

        assertEquals("Minimum stereo buffer size preserved", 2, minOutput.size)
        assertEquals("Large buffer size preserved", largeInput.size, largeOutput.size)
    }

    // ── Enabled / disabled state ──────────────────────────────────────────────

    @Test
    fun `isEnabled is true by default`() {
        assertTrue("Filter should be enabled by default", directionalFilter.isEnabled)
    }

    @Test
    fun `isEnabled toggles correctly`() {
        directionalFilter.setEnabled(false)
        assertFalse("Should be disabled after setEnabled(false)", directionalFilter.isEnabled)

        directionalFilter.setEnabled(true)
        assertTrue("Should be enabled after setEnabled(true)", directionalFilter.isEnabled)
    }

    // ── MAX_DELAY_SAMPLES sanity ──────────────────────────────────────────────

    @Test
    fun `max delay samples is positive and within physical bounds`() {
        // max_delay = round(0.15 m × 16 000 Hz / 343 m/s) ≈ 7 samples
        val maxDelay = DirectionalFilterImpl.MAX_DELAY_SAMPLES
        assertTrue("MAX_DELAY_SAMPLES should be positive", maxDelay > 0)
        assertTrue("MAX_DELAY_SAMPLES should be ≤ 20 (physical limit)", maxDelay <= 20)
    }

    // ── Sample-rate variants ──────────────────────────────────────────────────

    @Test
    fun `process handles multiple common sample rates`() {
        val buffer = ShortArray(320) { 100 }
        val sampleRates = intArrayOf(16000, 44100, 48000)

        sampleRates.forEach { sr ->
            val out = directionalFilter.process(buffer.clone(), sr, 2)
            assertEquals("Buffer size preserved at $sr Hz", buffer.size, out.size)
        }
    }

    // ── All direction types accepted ──────────────────────────────────────────

    @Test
    fun `all AudioDirection variants are accepted without crashing`() {
        val directions = listOf(
            AudioDirection.Front,
            AudioDirection.Left,
            AudioDirection.Right,
            AudioDirection.Rear,
            AudioDirection.Custom(0f),
            AudioDirection.Custom(45f),
            AudioDirection.Custom(90f),
            AudioDirection.Custom(-90f),
            AudioDirection.Custom(180f)
        )
        directions.forEach { directionalFilter.setDirection(it) }
        // No exception thrown → all variants accepted.
        assertTrue("All AudioDirection variants should be accepted", true)
    }

    // ── Output range ──────────────────────────────────────────────────────────

    @Test
    fun `process output samples are always within Short range`() {
        val buffer = ShortArray(320) { 500 }
        val output = directionalFilter.process(buffer, 16000, 2)

        output.forEachIndexed { i, sample ->
            assertTrue(
                "Sample $i (${sample.toInt()}) must be in [Short.MIN, Short.MAX]",
                sample.toInt() in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()
            )
        }
    }

    // ── Rapid direction changes ───────────────────────────────────────────────

    @Test
    fun `rapid direction changes with concurrent processing do not crash`() {
        val directions = arrayOf(
            AudioDirection.Front, AudioDirection.Left,
            AudioDirection.Right, AudioDirection.Rear
        )
        repeat(10) { i ->
            directionalFilter.setDirection(directions[i % 4])
            directionalFilter.process(ShortArray(320) { 100 }, 16000, 2)
        }
        assertTrue("Rapid direction changes should not crash", true)
    }
}
