package com.example.tryhearingaid.audio

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Unit tests for [SoundClassifierTap]. They use a window of 8 samples and a hop of 4 so the
 * expected windows can be written out by hand.
 */
class SoundClassifierTapTest {

    private fun shorts(range: IntRange): ShortArray = range.map { it.toShort() }.toShortArray()

    private class Recorder {
        val windows = mutableListOf<List<Short>>()
        val sink: (ShortArray) -> Unit = { windows += it.toList() }
    }

    private fun tap(recorder: Recorder, window: Int = 8, hop: Int = 4) =
        SoundClassifierTap(recorder.sink, window, hop)

    @Test
    fun `the audio is passed through unchanged`() {
        val tap = tap(Recorder())
        val input = shorts(1..4)

        val output = tap.process(input, 16_000, 1)

        output.toList().shouldContainExactly(1.toShort(), 2.toShort(), 3.toShort(), 4.toShort())
    }

    @Test
    fun `nothing is sent until a full window has been heard`() {
        val recorder = Recorder()
        val tap = tap(recorder)

        tap.process(shorts(1..4), 16_000, 1)

        recorder.windows.shouldBeEmpty()
    }

    @Test
    fun `the first full window is sent in time order`() {
        val recorder = Recorder()
        val tap = tap(recorder)

        tap.process(shorts(1..4), 16_000, 1)
        tap.process(shorts(5..8), 16_000, 1)

        recorder.windows.size shouldBe 1
        recorder.windows[0].shouldContainExactly((1..8).map { it.toShort() })
    }

    @Test
    fun `later windows slide forward by the hop and keep time order across the wrap`() {
        val recorder = Recorder()
        val tap = tap(recorder)

        tap.process(shorts(1..4), 16_000, 1)
        tap.process(shorts(5..8), 16_000, 1)   // window 1: 1..8
        tap.process(shorts(9..12), 16_000, 1)  // window 2: 5..12
        tap.process(shorts(13..16), 16_000, 1) // window 3: 9..16

        recorder.windows.size shouldBe 3
        recorder.windows[1].shouldContainExactly((5..12).map { it.toShort() })
        recorder.windows[2].shouldContainExactly((9..16).map { it.toShort() })
    }

    @Test
    fun `frames that do not divide the window evenly still give the newest samples`() {
        val recorder = Recorder()
        val tap = tap(recorder)

        tap.process(shorts(1..3), 16_000, 1)
        tap.process(shorts(4..6), 16_000, 1)
        tap.process(shorts(7..9), 16_000, 1)    // 9 samples heard: first window = 2..9
        tap.process(shorts(10..12), 16_000, 1)  // only 3 since the last window: nothing yet
        tap.process(shorts(13..15), 16_000, 1)  // 6 since the last window: window = 8..15

        recorder.windows.size shouldBe 2
        recorder.windows[0].shouldContainExactly((2..9).map { it.toShort() })
        recorder.windows[1].shouldContainExactly((8..15).map { it.toShort() })
    }

    @Test
    fun `a sent window is a copy so later audio cannot change it`() {
        val sent = mutableListOf<ShortArray>()
        val tap = SoundClassifierTap({ sent += it }, 8, 4)

        tap.process(shorts(1..8), 16_000, 1)
        val first = sent[0]
        first.fill(0)
        tap.process(shorts(9..12), 16_000, 1)

        sent[1].toList().shouldContainExactly((5..12).map { it.toShort() })
    }

    @Test
    fun `audio that is not 16 kHz mono is passed through and not classified`() {
        val recorder = Recorder()
        val tap = tap(recorder)

        tap.process(shorts(1..8), 48_000, 1)
        tap.process(shorts(1..8), 16_000, 2)

        recorder.windows.shouldBeEmpty()
    }

    @Test
    fun `an error from the sink does not reach the audio path`() {
        val tap = SoundClassifierTap({ throw IllegalStateException("classifier down") }, 8, 4)

        val output = tap.process(shorts(1..8), 16_000, 1) // must not throw

        output.size shouldBe 8
    }

    @Test
    fun `the tap can be switched off`() {
        val tap = tap(Recorder())

        tap.isEnabled shouldBe true
        tap.setEnabled(false)
        tap.isEnabled shouldBe false
    }

    @Test
    fun `the default window and hop match YAMNet`() {
        SoundClassifierTap.DEFAULT_WINDOW_SAMPLES shouldBe 15_600
        SoundClassifierTap.DEFAULT_HOP_SAMPLES shouldBe 7_800
    }
}
