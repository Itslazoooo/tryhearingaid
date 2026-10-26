package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

// ─── Property 28 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 28: Every detected sound event produces a non-empty natural language description

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SoundNarrationPropertyTest : FreeSpec({

    "Property 28: Every detected sound event produces a non-empty natural language description" {
        // Feature: advanced-hearing-aid-app, Property 28: Every detected sound event produces a non-empty natural language description

        var aiAnswer: String? = ""          // null means the AI call throws
        val assistant = mockk<AIAssistant>()
        coEvery { assistant.describeSound(any(), any()) } answers {
            aiAnswer ?: throw IllegalStateException("AI unavailable")
        }
        val narrator = SoundNarrator(assistant)

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.enum<SoundCategory>(),
            Arb.float(0f, 1f),
            Arb.string(0, 60),              // whatever the AI says, including blank
            Arb.int(0, 3)                   // 0 = AI throws, otherwise AI returns the string
        ) { category, confidence, text, mode ->
            aiAnswer = if (mode == 0) null else text
            val description = runBlocking {
                narrator.describe(SoundDetection(category, confidence, 1_000L))
            }
            description.shouldNotBeBlank()
            description.lowercase() shouldContain SoundNarrator.categoryLabel(category)
        }
    }
})

// ─── Property 29 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 29: Sound description queue depth never exceeds 5

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class DescriptionQueueDepthPropertyTest : FreeSpec({

    "Property 29: Sound description queue depth never exceeds 5" {
        // Feature: advanced-hearing-aid-app, Property 29: Sound description queue depth never exceeds 5

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(Arb.enum<SoundCategory>(), 6..50)
        ) { categories ->
            val queue = DescriptionQueue()
            categories.forEachIndexed { index, category ->
                // 10 s apart so no two events merge; this isolates the depth rule.
                val after = queue.add(category, "event $index", index * 10_000L)
                after.size shouldBeLessThanOrEqual 5
                after.size shouldBe minOf(index + 1, 5)
            }
            // The oldest events were discarded: the survivors are the last five added.
            queue.items.map { it.text } shouldBe (categories.size - 5 until categories.size).map { "event $it" }
        }
    }
})

// ─── Property 30 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 30: Same-category events within 3 seconds are merged

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class DescriptionQueueMergePropertyTest : FreeSpec({

    "Property 30: Same-category events within 3 seconds are merged" {
        // Feature: advanced-hearing-aid-app, Property 30: Same-category events within 3 seconds are merged

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.enum<SoundCategory>(),
            Arb.long(0L, 1_000_000_000L),   // first event time
            Arb.long(0L, 3_000L)            // gap to the second event, inclusive of 3000
        ) { category, start, gap ->
            val queue = DescriptionQueue()
            queue.add(category, "first", start)
            queue.add(category, "second", start + gap)

            queue.items shouldHaveSize 1
            queue.items.single().count shouldBe 2
        }
    }
})

// ─── Unit tests ───────────────────────────────────────────────────────────────

class DescriptionQueueTest {

    @Test
    fun `same category more than 3 seconds apart gives two cards`() {
        val queue = DescriptionQueue()
        queue.add(SoundCategory.DOORBELL, "a", 0L)
        queue.add(SoundCategory.DOORBELL, "b", 3_001L)
        queue.items shouldHaveSize 2
    }

    @Test
    fun `different categories at the same time are never merged`() {
        val queue = DescriptionQueue()
        queue.add(SoundCategory.DOORBELL, "a", 0L)
        queue.add(SoundCategory.KNOCK, "b", 0L)
        queue.items shouldHaveSize 2
    }

    @Test
    fun `a merged card keeps the latest text and the time span`() {
        val queue = DescriptionQueue()
        queue.add(SoundCategory.DOG_BARKING, "old", 1_000L)
        queue.add(SoundCategory.DOG_BARKING, "new", 2_500L)
        val card = queue.items.single()
        card.text shouldBe "new"
        card.firstTimestampMs shouldBe 1_000L
        card.lastTimestampMs shouldBe 2_500L
    }

    @Test
    fun `cards are removed after the 5 second display time`() {
        val queue = DescriptionQueue()
        queue.add(SoundCategory.KNOCK, "a", 0L)
        queue.expire(4_999L) shouldHaveSize 1
        queue.expire(5_000L) shouldHaveSize 0
    }

    @Test
    fun `a full queue drops the oldest card when a new one arrives`() {
        val queue = DescriptionQueue()
        SoundCategory.entries.take(5).forEachIndexed { i, c -> queue.add(c, "e$i", i * 10_000L) }
        queue.add(SoundCategory.SIREN, "new", 100_000L)
        queue.items.map { it.text } shouldBe listOf("e1", "e2", "e3", "e4", "new")
    }
}

class SoundNarratorTest {

    private fun narratorReturning(answer: String?): SoundNarrator {
        val assistant = mockk<AIAssistant>()
        coEvery { assistant.describeSound(any(), any()) } answers {
            answer ?: throw IllegalStateException("offline")
        }
        return SoundNarrator(assistant)
    }

    private fun describe(narrator: SoundNarrator, category: SoundCategory) =
        runBlocking { narrator.describe(SoundDetection(category, 0.9f, 0L)) }

    @Test
    fun `a good AI description is returned unchanged`() {
        describe(narratorReturning("Someone is ringing the doorbell"), SoundCategory.DOORBELL) shouldBe
            "Someone is ringing the doorbell"
    }

    @Test
    fun `an AI description without the category gets the label appended`() {
        describe(narratorReturning("Someone is calling your name"), SoundCategory.NAME_CALLED) shouldBe
            "Someone is calling your name (name called)"
    }

    @Test
    fun `an AI failure falls back to a built-in sentence`() {
        describe(narratorReturning(null), SoundCategory.BABY_CRYING) shouldBe
            "A baby crying was detected nearby"
    }
}
