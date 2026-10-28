package com.example.tryhearingaid.audio

import io.kotest.matchers.shouldBe
import org.junit.Test

class PlainGainTest {

    @Test
    fun `gain only multiplies the samples`() {
        applyPlainGain(shortArrayOf(100, -200, 0), 2f).toList() shouldBe listOf<Short>(200, -400, 0)
    }

    @Test
    fun `gain 1 leaves the sound exactly as it was`() {
        val input = shortArrayOf(1, -1, 1234, -32768, 32767)
        applyPlainGain(input.copyOf(), 1f).toList() shouldBe input.toList()
    }

    @Test
    fun `more gain gives a larger output and values are cut at the 16 bit limit`() {
        val quiet = applyPlainGain(shortArrayOf(1000), 2f)[0].toInt()
        val loud = applyPlainGain(shortArrayOf(1000), 10f)[0].toInt()
        (loud > quiet) shouldBe true
        applyPlainGain(shortArrayOf(30000, -30000), 100f).toList() shouldBe listOf<Short>(32767, -32768)
    }
}
