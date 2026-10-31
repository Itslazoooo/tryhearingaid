package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.data.SoundCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.Test

class SoundDescriptionsTest {

    @Test
    fun `every category has a description that names the sound`() {
        SoundCategory.entries.forEach { category ->
            val text = SoundDescriptions.describe(category, 0.9f)
            text.shouldNotBeBlank()
            text.lowercase() shouldContain SoundNarrator.categoryLabel(category)
        }
    }

    @Test
    fun `low confidence is marked as not certain`() {
        SoundDescriptions.describe(SoundCategory.DOORBELL, 0.5f) shouldContain "Not certain"
        (SoundDescriptions.describe(SoundCategory.DOORBELL, 0.9f).contains("Not certain")) shouldBe false
    }

    @Test
    fun `confidence is read from the narrator context`() {
        SoundDescriptions.confidenceFrom("confidence 85 percent") shouldBe 0.85f
        SoundDescriptions.confidenceFrom("confidence 250 percent") shouldBe 1f
        SoundDescriptions.confidenceFrom("no number here") shouldBe 1f
    }

    @Test
    fun `the narrator uses these descriptions without appending the label again`() {
        // A description that already names the sound is used as it is.
        val text = SoundDescriptions.describe(SoundCategory.BABY_CRYING, 0.95f)
        text.lowercase().contains("baby crying") shouldBe true
    }
}
