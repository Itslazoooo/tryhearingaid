package com.example.tryhearingaid.audio

import com.example.tryhearingaid.audio.AmplifierAction.NONE
import com.example.tryhearingaid.audio.AmplifierAction.RESTART
import com.example.tryhearingaid.audio.AmplifierAction.START
import com.example.tryhearingaid.audio.AmplifierAction.STOP
import io.kotest.matchers.shouldBe
import org.junit.Test

class AmplifierPolicyTest {

    private fun decide(wanted: Boolean, headphones: Boolean, active: Boolean, rate: Int = 48_000, running: Int = 48_000) =
        AmplifierPolicy.decide(wanted, headphones, active, rate, running)

    @Test
    fun `it never runs without headphones`() {
        decide(wanted = true, headphones = false, active = false) shouldBe NONE
    }

    @Test
    fun `it stops as soon as headphones are removed`() {
        decide(wanted = true, headphones = false, active = true) shouldBe STOP
    }

    @Test
    fun `it starts when headphones are connected and the user wants it on`() {
        decide(wanted = true, headphones = true, active = false) shouldBe START
    }

    @Test
    fun `it stays off when the user switched it off`() {
        decide(wanted = false, headphones = true, active = false) shouldBe NONE
        decide(wanted = false, headphones = true, active = true) shouldBe STOP
    }

    @Test
    fun `a changed sample rate restarts a running amplifier only`() {
        decide(wanted = true, headphones = true, active = true, rate = 16_000, running = 48_000) shouldBe RESTART
        decide(wanted = true, headphones = true, active = true, rate = 48_000, running = 48_000) shouldBe NONE
        decide(wanted = true, headphones = false, active = false, rate = 16_000, running = 48_000) shouldBe NONE
    }

    @Test
    fun `wired, bluetooth, usb and hearing aid outputs count as headphones`() {
        listOf(3, 4, 8, 22, 23, 26).forEach { HeadphonePolicy.isHeadphone(it) shouldBe true }
    }

    @Test
    fun `the phone speaker and earpiece do not`() {
        // 2 = built-in speaker, 1 = earpiece, 7 = bluetooth call audio, 9 = HDMI
        listOf(1, 2, 7, 9).forEach { HeadphonePolicy.isHeadphone(it) shouldBe false }
        HeadphonePolicy.anyHeadphone(listOf(1, 2)) shouldBe false
        HeadphonePolicy.anyHeadphone(listOf(2, 8)) shouldBe true
        HeadphonePolicy.anyHeadphone(emptyList()) shouldBe false
    }
}
