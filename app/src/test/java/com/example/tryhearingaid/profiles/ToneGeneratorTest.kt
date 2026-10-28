package com.example.tryhearingaid.profiles

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Test

class ToneGeneratorTest {

    private fun peak(samples: ShortArray, channel: Int): Int =
        (channel until samples.size step 2).maxOf { abs(samples[it].toInt()) }

    @Test
    fun `each 5 dBHL step is 5 dB`() {
        val db = 20 * log10(ToneGenerator.amplitudeFor(45) / ToneGenerator.amplitudeFor(40))
        db shouldBe (5.0 plusOrMinus 0.001)
    }

    @Test
    fun `80 dBHL is minus 10 dBFS and levels are clamped`() {
        val db = 20 * log10(ToneGenerator.amplitudeFor(80))
        db shouldBe (-10.0 plusOrMinus 0.001)
        ToneGenerator.amplitudeFor(200) shouldBe ToneGenerator.amplitudeFor(80)
        ToneGenerator.amplitudeFor(-5) shouldBe ToneGenerator.amplitudeFor(0)
    }

    @Test
    fun `left ear tone is only in the left channel`() {
        val s = ToneGenerator.generate(1000, 60, Ear.LEFT)
        (peak(s, 0) > 0) shouldBe true
        peak(s, 1) shouldBe 0
    }

    @Test
    fun `right ear tone is only in the right channel`() {
        val s = ToneGenerator.generate(1000, 60, Ear.RIGHT)
        peak(s, 0) shouldBe 0
        (peak(s, 1) > 0) shouldBe true
    }

    @Test
    fun `one second is stereo and loops without a jump`() {
        val s = ToneGenerator.generate(500, 60, Ear.LEFT)
        s.size shouldBe ToneGenerator.SAMPLE_RATE * 2
        s[0].toInt() shouldBe 0 // starts at zero crossing, and a whole number of cycles fit
    }

    @Test
    fun `louder levels give larger samples`() {
        (peak(ToneGenerator.generate(1000, 80, Ear.LEFT), 0) >
            peak(ToneGenerator.generate(1000, 60, Ear.LEFT), 0)) shouldBe true
    }
}
