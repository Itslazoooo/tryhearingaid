package com.example.tryhearingaid.translation

import android.content.Context
import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.captions.FakeCaptionEngine
import com.example.tryhearingaid.captions.InMemoryTranscriptStore
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.ui.captions.CaptionViewModel
import com.example.tryhearingaid.ui.captions.SUPPORTED_CAPTION_LANGUAGES
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionTranslationTest {

    private class FakeTranslator(var result: TranslationResult? = null) : TextTranslator {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun translate(text: String, from: String, to: String): TranslationResult {
            calls += Triple(text, from, to)
            return result ?: TranslationResult.Text("[$to] $text")
        }
    }

    private lateinit var engine: FakeCaptionEngine
    private lateinit var translator: FakeTranslator
    private lateinit var viewModel: CaptionViewModel

    private val spanish = SUPPORTED_CAPTION_LANGUAGES.first { it.code == "es" }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        engine = FakeCaptionEngine()
        translator = FakeTranslator()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns File(System.getProperty("java.io.tmpdir"), "test_files")
        }
        viewModel = CaptionViewModel(
            captionEngine = engine,
            transcriptStore = InMemoryTranscriptStore(),
            aiAssistant = mockk<AIAssistant>(relaxed = true),
            appContext = context,
            translator = translator
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun segment(text: String) = CaptionSegment(
        id = UUID.randomUUID(), speakerLabel = "Speaker 1", text = text,
        confidence = 0.9f, timestampMs = 1L, isUncertain = false
    )

    @Test
    fun `nothing is translated until translation is switched on`() = runTest {
        engine.pushSegment(segment("hello"))
        translator.calls.size shouldBe 0
    }

    @Test
    fun `new captions get a translation under them`() = runTest {
        viewModel.selectTranslationTarget(spanish)
        viewModel.setTranslationEnabled(true)
        val s = segment("hello")

        engine.pushSegment(s)

        viewModel.uiState.value.translations[s.id] shouldBe "[es] hello"
        translator.calls.single() shouldBe Triple("hello", "en", "es")
    }

    @Test
    fun `captions that already exist are translated when translation is switched on`() = runTest {
        val s = segment("good morning")
        engine.pushSegment(s)

        viewModel.setTranslationEnabled(true)

        viewModel.uiState.value.translations[s.id] shouldBe "[en] good morning"
    }

    @Test
    fun `a caption is translated only once`() = runTest {
        viewModel.setTranslationEnabled(true)
        engine.pushSegment(segment("one"))
        engine.pushSegment(segment("two"))

        translator.calls.map { it.first } shouldBe listOf("one", "two")
    }

    @Test
    fun `changing the target language translates again`() = runTest {
        viewModel.setTranslationEnabled(true)
        val s = segment("hello")
        engine.pushSegment(s)

        viewModel.selectTranslationTarget(spanish)

        viewModel.uiState.value.translations[s.id] shouldBe "[es] hello"
    }

    @Test
    fun `a failed translation shows why and keeps the caption`() = runTest {
        translator.result = TranslationResult.Unavailable("Waiting for Wi-Fi")
        viewModel.setTranslationEnabled(true)
        val s = segment("hello")

        engine.pushSegment(s)

        viewModel.uiState.value.translationStatus shouldBe "Waiting for Wi-Fi"
        viewModel.uiState.value.translations.containsKey(s.id) shouldBe false
        viewModel.uiState.value.captions.size shouldBe 1
    }

    @Test
    fun `app language codes map to the translation library`() {
        TranslationLanguages.mlKitTag("fil") shouldBe "tl"
        TranslationLanguages.mlKitTag("en-US") shouldBe "en"
        TranslationLanguages.mlKitTag("pt_BR") shouldBe "pt"
        TranslationLanguages.mlKitTag("xx") shouldBe null
        SUPPORTED_CAPTION_LANGUAGES.forEach { TranslationLanguages.mlKitTag(it.code) shouldBe (if (it.code == "fil") "tl" else it.code) }
    }

    @Test
    fun `without a translator the app says translation is not available`() = runTest {
        (NoTranslation.translate("a", "en", "es") is TranslationResult.Unavailable) shouldBe true
    }
}
