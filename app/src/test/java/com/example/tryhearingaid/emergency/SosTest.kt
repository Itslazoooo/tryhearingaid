package com.example.tryhearingaid.emergency

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import java.time.ZoneOffset

class SosMessageBuilderTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun `a fresh fix is included with coordinates and a map link`() {
        val fix = SosLocation(14.599512, 120.984222, 0L)
        val text = SosMessageBuilder.build(current = fix, lastKnown = null, zone = utc)
        text shouldContain "14.599512,120.984222"
        text shouldContain "https://maps.google.com/?q=14.599512,120.984222"
        text shouldNotContain "outdated"
    }

    @Test
    fun `when GPS is unavailable the last known fix is sent with a staleness note`() {
        // 2026-10-26 10:15 UTC
        val stamp = java.time.ZonedDateTime.of(2026, 10, 26, 10, 15, 0, 0, utc).toInstant().toEpochMilli()
        val old = SosLocation(1.5, 2.5, stamp)
        val text = SosMessageBuilder.build(current = null, lastKnown = old, zone = utc)
        text shouldContain "Last known location"
        text shouldContain "as of 2026-10-26 10:15"
        text shouldContain "may be outdated"
        text shouldContain "1.500000,2.500000"
    }

    @Test
    fun `the fresh fix wins over the last known one`() {
        val fresh = SosLocation(1.0, 1.0, 0L)
        val old = SosLocation(9.0, 9.0, 0L)
        val text = SosMessageBuilder.build(current = fresh, lastKnown = old, zone = utc)
        text shouldContain "1.000000,1.000000"
        text shouldNotContain "9.000000"
    }

    @Test
    fun `with no location at all the message says so`() {
        SosMessageBuilder.build(current = null, lastKnown = null, zone = utc) shouldContain "Location unavailable"
    }
}

class SosCoordinatorTest {

    private class FakeSms(private val failuresBeforeSuccess: Map<String, Int> = emptyMap()) : SmsSender {
        val attempts = mutableMapOf<String, Int>()
        override fun send(phoneNumber: String, message: String): Boolean {
            val n = (attempts[phoneNumber] ?: 0) + 1
            attempts[phoneNumber] = n
            val failFor = failuresBeforeSuccess[phoneNumber] ?: 0
            return n > failFor
        }
    }

    @Test
    fun `an empty contact list sends nothing and reports NoContacts`() {
        val sms = FakeSms()
        SosCoordinator(sms).trigger(emptyList(), "help") shouldBe SosResult.NoContacts
        SosCoordinator(sms).trigger(listOf("  ", ""), "help") shouldBe SosResult.NoContacts
        sms.attempts.isEmpty() shouldBe true
    }

    @Test
    fun `at most 5 contacts are messaged`() {
        val sms = FakeSms()
        val result = SosCoordinator(sms).trigger((1..8).map { "+63900000000$it" }, "help")
        (result as SosResult.Completed).sentTo.size shouldBe 5
        sms.attempts.size shouldBe 5
    }

    @Test
    fun `duplicate numbers are messaged once`() {
        val sms = FakeSms()
        SosCoordinator(sms).trigger(listOf("+1", "+1", " +1 "), "help")
        sms.attempts["+1"] shouldBe 1
    }

    @Test
    fun `a failed send is retried up to 3 times and then succeeds`() {
        val sms = FakeSms(failuresBeforeSuccess = mapOf("+1" to 3))   // 3 failures, 4th attempt works
        val result = SosCoordinator(sms).trigger(listOf("+1"), "help") as SosResult.Completed
        result.sentTo shouldBe listOf("+1")
        sms.attempts["+1"] shouldBe 4
    }

    @Test
    fun `a contact that keeps failing is reported as failed after 3 retries`() {
        val sms = FakeSms(failuresBeforeSuccess = mapOf("+1" to 99))
        val result = SosCoordinator(sms).trigger(listOf("+1"), "help") as SosResult.Completed
        result.failed shouldBe listOf("+1")
        result.allFailed shouldBe true
        sms.attempts["+1"] shouldBe 4
    }

    @Test
    fun `one failing contact does not stop the others`() {
        val sms = FakeSms(failuresBeforeSuccess = mapOf("+1" to 99))
        val result = SosCoordinator(sms).trigger(listOf("+1", "+2"), "help") as SosResult.Completed
        result.sentTo shouldBe listOf("+2")
        result.failed shouldBe listOf("+1")
        result.allFailed shouldBe false
    }

    @Test
    fun `a sender that throws counts as a failure and does not crash`() {
        val sms = SmsSender { _, _ -> throw SecurityException("no SEND_SMS permission") }
        val result = SosCoordinator(sms).trigger(listOf("+1"), "help") as SosResult.Completed
        result.allFailed shouldBe true
    }
}
