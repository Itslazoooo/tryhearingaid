package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [NarrationController] using virtual time (Requirements 15.1 to 15.5).
 *
 * The AI assistant returns blank text, so every description is the narrator's built-in
 * fallback "A <label> was detected nearby", which keeps the expected text predictable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NarrationControllerTest {

    private val assistant = mockk<AIAssistant> {
        coEvery { describeSound(any(), any()) } returns ""
    }

    private fun detection(category: SoundCategory, atMs: Long) =
        SoundDetection(category = category, confidence = 0.9f, timestampMs = atMs)

    private class Fixture(
        val flow: MutableSharedFlow<SoundDetection>,
        val controller: NarrationController,
        val spoken: MutableList<String>
    )

    private fun TestScope.fixture(): Fixture {
        val flow = MutableSharedFlow<SoundDetection>()
        val spoken = mutableListOf<String>()
        val controller = NarrationController(
            scope = backgroundScope,
            detections = flow,
            narrator = SoundNarrator(assistant),
            clock = { currentTime },
            speak = { spoken += it }
        )
        controller.start()
        runCurrent() // let the collector subscribe before anything is emitted
        return Fixture(flow, controller, spoken)
    }

    @Test
    fun `a detection becomes a card that describes the sound`() = runTest {
        val f = fixture()

        f.flow.emit(detection(SoundCategory.DOORBELL, currentTime))
        runCurrent()

        f.controller.cards.value.map { it.text }.shouldContainExactly("A doorbell was detected nearby")
    }

    @Test
    fun `a card disappears after five seconds`() = runTest {
        val f = fixture()
        f.flow.emit(detection(SoundCategory.DOORBELL, currentTime))
        runCurrent()

        advanceTimeBy(4_000)
        runCurrent()
        f.controller.cards.value.size shouldBe 1

        advanceTimeBy(1_001)
        runCurrent()
        f.controller.cards.value.shouldBeEmpty()
    }

    @Test
    fun `same-category events within three seconds share one card`() = runTest {
        val f = fixture()

        f.flow.emit(detection(SoundCategory.KNOCK, currentTime))
        runCurrent()
        advanceTimeBy(2_000)
        f.flow.emit(detection(SoundCategory.KNOCK, currentTime))
        runCurrent()

        val cards = f.controller.cards.value
        cards.size shouldBe 1
        cards.single().count shouldBe 2
    }

    @Test
    fun `same-category events more than three seconds apart get separate cards`() = runTest {
        val f = fixture()

        f.flow.emit(detection(SoundCategory.KNOCK, currentTime))
        runCurrent()
        advanceTimeBy(3_500)
        f.flow.emit(detection(SoundCategory.KNOCK, currentTime))
        runCurrent()

        f.controller.cards.value.size shouldBe 2
    }

    @Test
    fun `no more than five cards are ever shown`() = runTest {
        val f = fixture()
        val categories = listOf(
            SoundCategory.DOORBELL, SoundCategory.BABY_CRYING, SoundCategory.FIRE_ALARM,
            SoundCategory.CAR_HORN, SoundCategory.DOG_BARKING, SoundCategory.KNOCK,
            SoundCategory.PHONE_RINGING, SoundCategory.WATER_RUNNING
        )

        categories.forEach {
            f.flow.emit(detection(it, currentTime))
            runCurrent()
            (f.controller.cards.value.size <= 5) shouldBe true
        }

        f.controller.cards.value.size shouldBe 5
        // The oldest three were discarded, the newest five remain.
        f.controller.cards.value.map { it.category }.shouldContainExactly(categories.takeLast(5))
    }

    @Test
    fun `speech is requested once per new card and not again for a merge`() = runTest {
        val f = fixture()

        f.flow.emit(detection(SoundCategory.DOORBELL, currentTime))
        runCurrent()
        advanceTimeBy(1_000)
        f.flow.emit(detection(SoundCategory.DOORBELL, currentTime)) // merges
        runCurrent()
        f.spoken.shouldContainExactly("A doorbell was detected nearby")

        f.flow.emit(detection(SoundCategory.DOG_BARKING, currentTime)) // new card
        runCurrent()
        f.spoken.shouldContainExactly(
            "A doorbell was detected nearby",
            "A dog barking was detected nearby"
        )
    }

    @Test
    fun `stop clears the cards and ignores later detections`() = runTest {
        val f = fixture()
        f.flow.emit(detection(SoundCategory.DOORBELL, currentTime))
        runCurrent()
        f.controller.cards.value.size shouldBe 1

        f.controller.stop()
        runCurrent()
        f.controller.cards.value.shouldBeEmpty()

        f.flow.emit(detection(SoundCategory.KNOCK, currentTime)) // nobody is collecting; returns at once
        runCurrent()
        f.controller.cards.value.shouldBeEmpty()
    }
}
