package com.example.tryhearingaid.emergency

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Unit tests for [SosContacts] (Requirement 10.3: up to 5 saved contacts). */
class SosContactsTest {

    @Test
    fun `normalize trims, drops blanks and duplicates and keeps order`() {
        SosContacts.normalize(listOf(" 111 ", "", "   ", "222", "111", "333"))
            .shouldContainExactly("111", "222", "333")
    }

    @Test
    fun `normalize keeps only the first five contacts`() {
        val seven = (1..7).map { "10$it" }
        SosContacts.normalize(seven).shouldContainExactly("101", "102", "103", "104", "105")
        SosContacts.MAX_CONTACTS shouldBe 5
    }

    @Test
    fun `encode then decode returns the same cleaned list`() {
        val numbers = listOf("+63 912 345 6789", "0917-111-2222", "112")
        SosContacts.decode(SosContacts.encode(numbers)).shouldContainExactly(numbers)
    }

    @Test
    fun `decode of null or empty text is an empty list`() {
        SosContacts.decode(null).shouldBeEmpty()
        SosContacts.decode("").shouldBeEmpty()
    }

    @Test
    fun `decode never returns more than five contacts even from oversized stored text`() {
        val stored = (1..9).joinToString("\n") { "20$it" }
        SosContacts.decode(stored).size shouldBe 5
    }
}
