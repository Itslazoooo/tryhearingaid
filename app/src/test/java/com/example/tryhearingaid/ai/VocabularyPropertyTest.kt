package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.VocabTermEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import java.util.UUID

// ─── Property 24 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 24: New vocabulary words are added with non-empty definitions

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class VocabularyNewWordPropertyTest : FreeSpec({

    "Property 24: New vocabulary words are added with non-empty definitions" {
        // Feature: advanced-hearing-aid-app, Property 24: New vocabulary words are added with non-empty definitions
        // Generate random novel words; assert vocabulary list contains entry with non-empty definition
        // after processing a segment containing that word.

        checkAll(
            PropTestConfig(iterations = 100),
            // Generate a random word (6-15 lowercase letters) to use as a novel vocabulary word
            Arb.string(6, 15, Arb.element(('a'..'z').map { Codepoint(it.code) }))
        ) { novelWord ->
            val vocabularyDao = InMemoryVocabularyDao()

            // The fake client always returns a non-empty definition
            val fakeCloud = FakeGeminiClient()
            val fakeOnDevice = FakeGeminiClient()
            val networkCallback = FakeNetworkCallback()
            networkCallback.isConnected = true

            val assistant = AIAssistantImpl(
                cloudClient = fakeCloud,
                onDeviceClient = fakeOnDevice,
                vocabularyDao = vocabularyDao,
                networkCallback = networkCallback,
                scope = CoroutineScope(Dispatchers.Unconfined)
            )

            // Confirm the word is not yet in vocabulary
            vocabularyDao.contains(novelWord) shouldNotBe true

            // Process a segment containing the novel word
            val segment = CaptionSegment(
                id = UUID.randomUUID(),
                speakerLabel = "Speaker 1",
                text = "The concept of $novelWord is very important in this field",
                confidence = 0.95f,
                timestampMs = System.currentTimeMillis(),
                isUncertain = false
            )
            assistant.processSegment(segment)

            // Only assert for words > 3 chars (implementation filters short words)
            if (novelWord.length > 3) {
                val allTerms = vocabularyDao.getAll()
                val entry = allTerms.firstOrNull { it.word.equals(novelWord, ignoreCase = true) }

                entry shouldNotBe null
                entry!!.definition.shouldNotBeEmpty()
            }
        }
    }
})

// ─── Property 25 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 25: Vocabulary list clear is idempotent

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class VocabularyClearIdempotentPropertyTest : FreeSpec({

    "Property 25: Vocabulary list clear is idempotent" {
        // Feature: advanced-hearing-aid-app, Property 25: Vocabulary list clear is idempotent
        // Clear empty list → still empty. Clear non-empty list twice → same empty result both times.

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(0, 20)  // number of pre-populated vocabulary entries (0 = empty list case)
        ) { entryCount ->
            val vocabularyDao = InMemoryVocabularyDao()

            // ── Case 1: clear empty list ──
            vocabularyDao.getAll().shouldBeEmpty()
            vocabularyDao.clear()
            vocabularyDao.getAll().shouldBeEmpty()   // still empty after clearing empty list

            // ── Case 2: populate then clear twice ──
            repeat(entryCount) { idx ->
                vocabularyDao.insert(
                    VocabTermEntity(
                        id = UUID.randomUUID(),
                        sessionId = UUID.randomUUID(),
                        word = "word$idx",
                        definition = "Definition $idx",
                        contextSnippet = "snippet $idx"
                    )
                )
            }

            // Verify expected state before clearing
            vocabularyDao.getAll() shouldHaveSize entryCount

            // First clear
            vocabularyDao.clear()
            vocabularyDao.getAll().shouldBeEmpty()   // empty after first clear

            // Second clear (idempotent — clearing already-empty list)
            vocabularyDao.clear()
            vocabularyDao.getAll().shouldBeEmpty()   // still empty after second clear
        }
    }
})
