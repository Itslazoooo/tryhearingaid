package com.example.tryhearingaid.captions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoskSupportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    // ── Results ───────────────────────────────────────────────────────────────

    @Test
    fun `a sentence is read with the average word confidence`() {
        val json = """{"text":"hello there","result":[{"conf":0.9,"word":"hello"},{"conf":0.7,"word":"there"}]}"""
        val u = VoskResults.parse(json)!!
        u.text shouldBe "hello there"
        u.confidence shouldBe (0.8f plusOrMinus 0.001f)
    }

    @Test
    fun `a sentence without word scores gets the default confidence`() {
        VoskResults.parse("""{"text":"hi"}""")!!.confidence shouldBe VoskResults.DEFAULT_CONFIDENCE
    }

    @Test
    fun `silence and broken text give nothing`() {
        VoskResults.parse("""{"text":""}""") shouldBe null
        VoskResults.parse("""{"text":"   "}""") shouldBe null
        VoskResults.parse("not json") shouldBe null
        VoskResults.parse("""{"partial":"he"}""") shouldBe null
    }

    // ── Speaker guess ─────────────────────────────────────────────────────────

    @Test
    fun `a pause and a big change in loudness starts a new speaker`() {
        val t = SpeakerTracker()
        t.labelFor(1000f, 10_000L) shouldBe "Speaker 1"
        t.labelFor(2000f, 12_000L) shouldBe "Speaker 2"
    }

    @Test
    fun `no pause or no change keeps the same speaker`() {
        val t = SpeakerTracker()
        t.labelFor(1000f, 10_000L)
        t.labelFor(2000f, 10_500L) shouldBe "Speaker 1" // loudness changed but no pause
        t.labelFor(2100f, 20_000L) shouldBe "Speaker 1" // pause but loudness barely changed
    }

    // ── Downloaded files ──────────────────────────────────────────────────────

    @Test
    fun `a model zip is unpacked and its folder is found`() {
        val dest = folder.newFolder("model")
        ZipExtractor.extract(
            zip("vosk-model-small-en-us-0.15/am/final.mdl" to "x", "vosk-model-small-en-us-0.15/conf/model.conf" to "y"),
            dest
        )
        val root = findModelRoot(dest)!!
        root.name shouldBe "vosk-model-small-en-us-0.15"
        File(root, "conf/model.conf").readText() shouldBe "y"
    }

    @Test
    fun `a zip that tries to write outside the folder is refused`() {
        val dest = folder.newFolder("model")
        shouldThrow<IOException> {
            ZipExtractor.extract(zip("../evil.txt" to "bad"), dest)
        }
        File(dest.parentFile, "evil.txt").exists() shouldBe false
    }

    @Test
    fun `a folder without a model is not a model`() {
        val dest = folder.newFolder("empty")
        findModelRoot(dest) shouldBe null
        findModelRoot(File(dest, "missing")) shouldBe null
    }

    @Test
    fun `the counting stream reports bytes read`() {
        var seen = 0L
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(100))) { seen = it }
        stream.readBytes()
        seen shouldBe 100L
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    @Test
    fun `language codes find their model and unsupported ones do not`() {
        VoskCatalog.forLanguage("en")!!.folder shouldBe "vosk-model-small-en-us-0.15"
        VoskCatalog.forLanguage("en-US")!!.language shouldBe "en"
        VoskCatalog.forLanguage("pt_BR")!!.language shouldBe "pt"
        VoskCatalog.forLanguage("fil") shouldBe null
        VoskCatalog.forLanguage("ar") shouldBe null
    }

    @Test
    fun `model links point at the Vosk download site over https`() {
        VoskCatalog.entries.forEach {
            it.url shouldBe "https://alphacephei.com/vosk/models/${it.folder}.zip"
        }
    }
}
