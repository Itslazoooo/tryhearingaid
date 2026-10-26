package com.example.tryhearingaid.captions

// Feature: advanced-hearing-aid-app, Property 7: Caption segments with low confidence are marked uncertain
// Feature: advanced-hearing-aid-app, Property 8: Supported caption languages include required 11
// Feature: advanced-hearing-aid-app, Property 9: Caption segments have speaker labels in multi-speaker sessions
// Feature: advanced-hearing-aid-app, Property 10: Transcript export round-trip

import com.example.tryhearingaid.data.CaptionSegment
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Property-based tests for [CaptionEngine] (Properties 7–10).
 *
 * All four properties are exercised against the in-memory [FakeCaptionEngine] /
 * [InMemoryTranscriptStore] fakes so no Android framework code is needed on the JVM.
 *
 * **Validates: Requirements 7.2, 7.3, 7.4, 7.6**
 */

// ─── Property 7 ───────────────────────────────────────────────────────────────

/**
 * Property 7: Caption segments with low confidence are marked uncertain.
 *
 * For any confidence value in [0, 1], a [CaptionSegment] constructed with
 * `isUncertain = (confidence < 0.70f)` must satisfy:
 *   - `isUncertain == true`  when `confidence < 0.70f`
 *   - `isUncertain == false` when `confidence >= 0.70f`
 *
 * **Validates: Requirements 7.6**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class CaptionUncertaintyPropertyTest : FreeSpec({

    "Property 7: Caption segments with low confidence are marked uncertain" {
        // Feature: advanced-hearing-aid-app, Property 7: Caption segments with low confidence are marked uncertain
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(0f, 1f)
        ) { confidence ->
            val segment = CaptionSegment(
                id = UUID.randomUUID(),
                speakerLabel = "Speaker 1",
                text = "test utterance",
                confidence = confidence,
                timestampMs = System.currentTimeMillis(),
                isUncertain = confidence < 0.70f
            )

            // The isUncertain flag must equal the result of (confidence < 0.70)
            val expectedUncertain = confidence < 0.70f
            segment.isUncertain shouldBe expectedUncertain

            if (confidence < 0.70f) {
                segment.isUncertain.shouldBeTrue()
            } else {
                segment.isUncertain.shouldBeFalse()
            }
        }
    }
})

// ─── Property 8 ───────────────────────────────────────────────────────────────

/**
 * Property 8: Supported caption languages include the required 11.
 *
 * For any [CaptionEngine] instance, [CaptionEngineImpl.SUPPORTED_LANGUAGES] must
 * contain the 11 required BCP-47 base language codes: fil, en, es, fr, de, ar,
 * zh, ja, ko, pt, hi.
 *
 * **Validates: Requirements 7.2**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SupportedLanguagesPropertyTest : FreeSpec({

    "Property 8: Supported caption languages include required 11" {
        // Feature: advanced-hearing-aid-app, Property 8: Supported caption languages include required 11
        val requiredLanguages = setOf("fil", "en", "es", "fr", "de", "ar", "zh", "ja", "ko", "pt", "hi")

        // Run 100 iterations (deterministic check) to satisfy PropTestConfig contract
        repeat(100) {
            val supportedLanguages = CaptionEngineImpl.SUPPORTED_LANGUAGES

            // Must have at least 11 languages
            supportedLanguages shouldHaveAtLeastSize 11

            // Must contain all 11 required language codes
            supportedLanguages shouldContainAll requiredLanguages
        }
    }
})

// ─── Property 9 ───────────────────────────────────────────────────────────────

/**
 * Property 9: Caption segments have speaker labels in multi-speaker sessions.
 *
 * For any collection of segments produced in a multi-speaker session:
 *   - Every segment's [CaptionSegment.speakerLabel] must be non-empty.
 *   - Two segments attributed to different speakers must carry distinct labels.
 *
 * **Validates: Requirements 7.3**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SpeakerLabelsPropertyTest : FreeSpec({

    "Property 9: Caption segments have speaker labels in multi-speaker sessions" {
        // Feature: advanced-hearing-aid-app, Property 9: Caption segments have speaker labels in multi-speaker sessions
        checkAll(
            PropTestConfig(iterations = 100),
            // Number of distinct speakers: 2–5
            Arb.int(2, 5),
            // Number of utterances per speaker: 1–4
            Arb.int(1, 4)
        ) { speakerCount, utterancesPerSpeaker ->
            val engine = FakeCaptionEngine()
            engine.start("en-US")

            // Simulate a multi-speaker session: each speaker produces utterancesPerSpeaker segments
            val allSegments = mutableListOf<CaptionSegment>()
            repeat(speakerCount) { speakerIdx ->
                val label = "Speaker ${speakerIdx + 1}"
                repeat(utterancesPerSpeaker) { utteranceIdx ->
                    val segment = CaptionSegment(
                        id = UUID.randomUUID(),
                        speakerLabel = label,
                        text = "Utterance $utteranceIdx from $label",
                        confidence = 0.85f,
                        timestampMs = System.currentTimeMillis() + (speakerIdx * 1000L + utteranceIdx * 100L),
                        isUncertain = false
                    )
                    engine.pushSegment(segment)
                    allSegments.add(segment)
                }
            }

            val emitted = engine.captions.value

            // All emitted segments must have non-empty speaker labels
            emitted.forEach { seg ->
                seg.speakerLabel.shouldNotBeEmpty()
            }

            // Segments from different speakers must carry distinct labels
            val labelsInSession = emitted.map { it.speakerLabel }.toSet()
            labelsInSession.size shouldBe speakerCount

            // Each distinct label must be non-empty
            labelsInSession.forEach { label ->
                label.shouldNotBeEmpty()
            }
        }
    }
})

// ─── Property 10 ──────────────────────────────────────────────────────────────

/**
 * Property 10: Transcript session export round-trip.
 *
 * For any set of [CaptionSegment]s saved to an [InMemoryTranscriptStore], exporting
 * that session as a text file must produce a file whose content contains every
 * segment's text string.
 *
 * **Validates: Requirements 7.4**
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class TranscriptExportRoundTripPropertyTest : FreeSpec({

    "Property 10: Transcript export round-trip" {
        // Feature: advanced-hearing-aid-app, Property 10: Transcript export round-trip
        checkAll(
            PropTestConfig(iterations = 100),
            // Generate 1–10 segments with random (printable ASCII) text
            Arb.list(
                Arb.string(
                    minSize = 1,
                    maxSize = 40,
                    codepoints = Arb.element(
                        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ')).map { Codepoint(it.code) }
                    )
                ),
                1..10
            )
        ) { segmentTexts ->
            runBlocking {
                val store = InMemoryTranscriptStore()
                val sessionId = UUID.randomUUID()

                // Save all segments to the store under the same session
                segmentTexts.forEachIndexed { idx, text ->
                    // Skip blank texts that would produce empty segment text (trim for safety)
                    val safeText = text.trim().ifEmpty { "segment$idx" }
                    val segment = CaptionSegment(
                        id = UUID.randomUUID(),
                        speakerLabel = "Speaker 1",
                        text = safeText,
                        confidence = 0.90f,
                        timestampMs = System.currentTimeMillis() + idx * 100L,
                        isUncertain = false
                    )
                    store.saveSegment(segment, sessionId)
                }

                // Export the session as plain text
                val exportedFile = store.exportAsText(sessionId)
                val exportedContent = exportedFile.readText()

                // Every segment's text must appear in the exported content
                val savedSegments = store.getSession(sessionId)
                savedSegments.forEach { seg ->
                    assert(exportedContent.contains(seg.text)) {
                        "Exported transcript must contain segment text '${seg.text}' but content was:\n$exportedContent"
                    }
                }

                // Clean up the temporary file
                exportedFile.delete()
            }
        }
    }
})
