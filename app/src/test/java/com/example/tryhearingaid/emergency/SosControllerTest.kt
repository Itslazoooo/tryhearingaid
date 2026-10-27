package com.example.tryhearingaid.emergency

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [SosController]: contact check, location choice, message text and retries
 * (Requirements 10.3, 10.4).
 */
class SosControllerTest {

    private class FakeLocation(
        private val fresh: SosLocation? = null,
        private val stored: SosLocation? = null
    ) : SosLocationSource {
        var currentCalls = 0
        override suspend fun current(): SosLocation? { currentCalls++; return fresh }
        override fun lastKnown(): SosLocation? = stored
    }

    private class RecordingSms(private val failFor: Set<String> = emptySet()) : SmsSender {
        val delivered = mutableListOf<Pair<String, String>>()
        val attempts = mutableMapOf<String, Int>()
        override fun send(phoneNumber: String, message: String): Boolean {
            attempts[phoneNumber] = (attempts[phoneNumber] ?: 0) + 1
            if (phoneNumber in failFor) return false
            delivered += phoneNumber to message
            return true
        }
    }

    private fun controller(
        contacts: List<String>,
        location: SosLocationSource,
        sms: SmsSender
    ) = SosController(
        contacts = { contacts },
        location = location,
        coordinator = SosCoordinator(sms),
        dispatcher = Dispatchers.Unconfined,
        zone = ZoneOffset.UTC
    )

    private val fix = SosLocation(latitude = 14.5995, longitude = 120.9842, timestampMs = 0L)

    @Test
    fun `no contacts means nothing is sent and no location is requested`() = runTest {
        val location = FakeLocation(fresh = fix)
        val sms = RecordingSms()

        controller(emptyList(), location, sms).trigger() shouldBe SosResult.NoContacts

        sms.delivered.shouldBeEmpty()
        location.currentCalls shouldBe 0
    }

    @Test
    fun `contacts that are only blank count as no contacts`() = runTest {
        val sms = RecordingSms()
        controller(listOf("", "   "), FakeLocation(fresh = fix), sms).trigger() shouldBe SosResult.NoContacts
        sms.delivered.shouldBeEmpty()
    }

    @Test
    fun `a fresh fix is sent as the location without an outdated note`() = runTest {
        val sms = RecordingSms()

        val result = controller(listOf("111", "222"), FakeLocation(fresh = fix), sms).trigger()

        (result as SosResult.Completed).sentTo.shouldContainExactly("111", "222")
        val text = sms.delivered.first().second
        text shouldContain "14.599500,120.984200"
        text shouldNotContain "outdated"
    }

    @Test
    fun `without a fresh fix the last known location is sent with a staleness note`() = runTest {
        val sms = RecordingSms()
        val location = FakeLocation(fresh = null, stored = fix)

        controller(listOf("111"), location, sms).trigger()

        val text = sms.delivered.single().second
        text shouldContain "Last known location"
        text shouldContain "may be outdated"
        text shouldContain "1970-01-01 00:00" // the fix's own time, in the zone given to the controller
        text shouldContain "14.599500,120.984200"
    }

    @Test
    fun `with no location at all the message says so and is still sent`() = runTest {
        val sms = RecordingSms()

        controller(listOf("111"), FakeLocation(), sms).trigger()

        sms.delivered.single().second shouldContain "Location unavailable"
    }

    @Test
    fun `only the first five contacts are messaged`() = runTest {
        val sms = RecordingSms()
        val seven = (1..7).map { "30$it" }

        controller(seven, FakeLocation(fresh = fix), sms).trigger()

        sms.delivered.map { it.first }.shouldContainExactly("301", "302", "303", "304", "305")
    }

    @Test
    fun `a failing number is retried three times then reported while the others still succeed`() = runTest {
        val sms = RecordingSms(failFor = setOf("bad"))

        val result = controller(listOf("good", "bad"), FakeLocation(fresh = fix), sms).trigger()

        val completed = result as SosResult.Completed
        completed.sentTo.shouldContainExactly("good")
        completed.failed.shouldContainExactly("bad")
        completed.allFailed shouldBe false
        sms.attempts["bad"] shouldBe 4 // first try plus three retries
        sms.attempts["good"] shouldBe 1
    }

    @Test
    fun `when every number fails the result says all failed`() = runTest {
        val sms = RecordingSms(failFor = setOf("111", "222"))

        val result = controller(listOf("111", "222"), FakeLocation(fresh = fix), sms).trigger()

        (result as SosResult.Completed).allFailed shouldBe true
    }
}
