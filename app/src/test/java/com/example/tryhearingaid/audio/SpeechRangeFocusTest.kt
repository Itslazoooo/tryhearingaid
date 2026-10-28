package com.example.tryhearingaid.audio

import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class SpeechRangeFocusTest {

    private val rate = 48_000

    private fun sine(hz: Int, n: Int = 9_600): ShortArray =
        ShortArray(n) { (10_000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun rmsOfLastHalf(a: ShortArray): Double {
        val half = a.size / 2
        var sum = 0.0
        for (i in half until a.size) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (a.size - half))
    }

    @Test
    fun `strength 0 returns the sound untouched`() {
        val input = sine(12_000)
        SpeechRangeFocus().apply(input, rate, 0f) shouldBe input
    }

    @Test
    fun `at full strength speech frequencies pass and very high and low ones are reduced`() {
        val ref = rmsOfLastHalf(sine(1_000))
        val speech = rmsOfLastHalf(SpeechRangeFocus().apply(sine(1_000), rate, 1f))
        val high = rmsOfLastHalf(SpeechRangeFocus().apply(sine(12_000), rate, 1f))
        val low = rmsOfLastHalf(SpeechRangeFocus().apply(sine(100), rate, 1f))

        (speech > ref * 0.8) shouldBe true
        (high < ref * 0.2) shouldBe true
        (low < ref * 0.3) shouldBe true
    }

    @Test
    fun `half strength reduces less than full strength`() {
        val half = rmsOfLastHalf(SpeechRangeFocus().apply(sine(12_000), rate, 0.5f))
        val full = rmsOfLastHalf(SpeechRangeFocus().apply(sine(12_000), rate, 1f))
        (half > full) shouldBe true
    }
}
