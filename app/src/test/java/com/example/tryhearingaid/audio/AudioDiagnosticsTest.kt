package com.example.tryhearingaid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsTest {

    private fun running(block: AudioDiagnostics.() -> AudioDiagnostics = { this }) =
        AudioDiagnostics(
            running = true, sampleRate = 48_000, frameMs = 10f, bufferMs = 80f,
            inputPeakDb = -20f, inputRmsDb = -30f, outputPeakDb = -10f, outputRmsDb = -20f,
            outputDeviceType = 4, micSource = "UNPROCESSED", rawMode = true
        ).block()

    private fun levels(d: AudioDiagnostics, native: Int = 48_000, frames: Int = 192) =
        AudioDiagnosticsAdvisor.advise(d, native, frames).map { it.level }

    @Test
    fun `accumulator measures peak rms and clipping`() {
        val acc = DiagnosticsAccumulator()
        val input = shortArrayOf(1000, -1000, 1000, -1000)
        val output = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, 0)
        acc.onFrame(input, 4, output, 4, 2_000_000L, 10_000_000L)
        val w = acc.takeWindow()
        assertEquals(2, w.clippedSamples)
        assertEquals(20, w.loadPercent)
        assertEquals(DiagnosticsAccumulator.toDb(1000.0), w.inputPeakDb, 0.01f)
        assertEquals(DiagnosticsAccumulator.toDb(1000.0), w.inputRmsDb, 0.01f)
        assertEquals(0f, w.outputPeakDb, 0.01f)
    }

    @Test
    fun `window resets after it is taken`() {
        val acc = DiagnosticsAccumulator()
        acc.onFrame(shortArrayOf(5000), 1, shortArrayOf(5000), 1, 1L, 10L)
        acc.takeWindow()
        val empty = acc.takeWindow()
        assertEquals(0, empty.frames)
        assertEquals(SILENCE_DB, empty.inputPeakDb, 0f)
    }

    @Test
    fun `silence has the floor level`() {
        assertEquals(SILENCE_DB, DiagnosticsAccumulator.toDb(0.0), 0f)
    }

    @Test
    fun `not running says so`() {
        val f = AudioDiagnosticsAdvisor.advise(AudioDiagnostics(), 48_000, 192)
        assertEquals(1, f.size)
        assertEquals(FindingLevel.INFO, f[0].level)
    }

    @Test
    fun `healthy audio reports no problems`() {
        assertEquals(listOf(FindingLevel.OK), levels(running()))
    }

    @Test
    fun `phone speaker is a problem`() {
        assertTrue(FindingLevel.PROBLEM in levels(running { copy(outputDeviceType = 2) }))
    }

    @Test
    fun `clipping is a problem`() {
        assertTrue(FindingLevel.PROBLEM in levels(running { copy(clippedSamples = 5) }))
    }

    @Test
    fun `recent dropouts are a problem and old ones a warning`() {
        assertTrue(FindingLevel.PROBLEM in levels(running { copy(underrunsRecent = 2, underrunsTotal = 2) }))
        val old = levels(running { copy(underrunsTotal = 3) })
        assertTrue(FindingLevel.WARNING in old)
        assertTrue(FindingLevel.PROBLEM !in old)
    }

    @Test
    fun `bluetooth call link is a problem and other bluetooth a note`() {
        assertTrue(FindingLevel.PROBLEM in levels(running { copy(outputDeviceType = 7) }))
        val a2dp = levels(running { copy(outputDeviceType = 8) })
        assertTrue(FindingLevel.INFO in a2dp)
        assertTrue(FindingLevel.PROBLEM !in a2dp)
    }

    @Test
    fun `quiet and overloaded microphones are warned about`() {
        assertTrue(FindingLevel.WARNING in levels(running { copy(inputRmsDb = -80f, inputPeakDb = -70f) }))
        assertTrue(FindingLevel.WARNING in levels(running { copy(inputPeakDb = -0.2f) }))
    }

    @Test
    fun `high load is a warning`() {
        assertTrue(FindingLevel.WARNING in levels(running { copy(maxLoadPercent = 80) }))
    }

    @Test
    fun `sample rate that differs from the phone is noted`() {
        val f = AudioDiagnosticsAdvisor.advise(running { copy(sampleRate = 16_000) }, 48_000, 192)
        assertTrue(f.any { it.level == FindingLevel.INFO && "48000" in it.text })
    }

    @Test
    fun `normal microphone in raw mode is noted`() {
        val f = AudioDiagnosticsAdvisor.advise(running { copy(micSource = "MIC") }, 48_000, 192)
        assertTrue(f.any { "MIC" in it.text })
    }

    @Test
    fun `report contains readings and findings`() {
        val d = running { copy(clippedSamples = 3) }
        val findings = AudioDiagnosticsAdvisor.advise(d, 48_000, 192)
        val text = AudioDiagnosticsAdvisor.report(d, findings, 48_000, 192)
        assertTrue("Clipped samples: 3" in text)
        assertTrue("wired headphones" in text)
        assertTrue("[PROBLEM]" in text)
    }

    @Test
    fun `device names are readable`() {
        assertEquals("phone speaker", AudioDiagnosticsAdvisor.deviceName(2))
        assertEquals("hearing aid", AudioDiagnosticsAdvisor.deviceName(23))
        assertEquals("unknown", AudioDiagnosticsAdvisor.deviceName(-1))
    }
}
